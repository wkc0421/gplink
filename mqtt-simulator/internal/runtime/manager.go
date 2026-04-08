package runtime

import (
	"context"
	"crypto/tls"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"

	"mqtt-simulator/internal/model"
	"mqtt-simulator/internal/store"
)

type Manager struct {
	store   *store.SQLiteStore
	broker  *Broker
	mu      sync.RWMutex
	tasks   map[string]*runningTask
	runtime map[string]model.TaskRuntime
}

type runningTask struct {
	config       model.TaskConfig
	runtime      model.TaskRuntime
	cancel       context.CancelFunc
	client       mqtt.Client
	builder      *PayloadBuilder
	lastSecond   atomic.Int64
	lastMinute   atomic.Int64
	sentTotal    atomic.Int64
	failedTotal  atomic.Int64
	payloadBytes atomic.Int64
}

func NewManager(store *store.SQLiteStore, broker *Broker) (*Manager, error) {
	runtimes, err := store.LoadRuntimes()
	if err != nil {
		return nil, err
	}
	return &Manager{
		store:   store,
		broker:  broker,
		tasks:   map[string]*runningTask{},
		runtime: runtimes,
	}, nil
}

func (m *Manager) List(configs []model.TaskConfig) []model.TaskSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	out := make([]model.TaskSnapshot, 0, len(configs))
	for _, cfg := range configs {
		rt, ok := m.runtime[cfg.ID]
		if !ok {
			rt = model.TaskRuntime{Status: "idle"}
		}
		if running, exists := m.tasks[cfg.ID]; exists {
			rt = running.snapshot()
		}
		out = append(out, model.TaskSnapshot{
			ID:      cfg.ID,
			Config:  cfg,
			Runtime: rt,
		})
	}
	return out
}

func (m *Manager) GetRuntime(id string) model.TaskRuntime {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if running, ok := m.tasks[id]; ok {
		return running.snapshot()
	}
	if rt, ok := m.runtime[id]; ok {
		return rt
	}
	return model.TaskRuntime{Status: "idle"}
}

func (m *Manager) Start(config model.TaskConfig) error {
	m.mu.Lock()
	if _, exists := m.tasks[config.ID]; exists {
		m.mu.Unlock()
		return fmt.Errorf("task already running")
	}
	m.mu.Unlock()

	counters, err := m.store.LoadECounters(config.ID)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	task := &runningTask{
		config:  config,
		runtime: model.TaskRuntime{Status: "starting"},
		cancel:  cancel,
		builder: NewPayloadBuilder(config.ID, config, counters),
	}

	if err = task.connect(); err != nil {
		cancel()
		task.runtime.Status = "error"
		task.runtime.LastError = err.Error()
		m.updateRuntime(config.ID, task.runtime)
		return err
	}

	now := time.Now()
	task.runtime.Status = "running"
	task.runtime.Connected = true
	task.runtime.StartedAt = &now

	m.mu.Lock()
	m.tasks[config.ID] = task
	m.mu.Unlock()
	m.updateRuntime(config.ID, task.snapshot())

	go task.run(ctx, m)
	go task.flushLoop(ctx, m.store)
	go task.statsLoop(ctx, m)
	return nil
}

func (m *Manager) Stop(id string) error {
	m.mu.Lock()
	task, exists := m.tasks[id]
	if exists {
		delete(m.tasks, id)
	}
	m.mu.Unlock()
	if !exists {
		return nil
	}

	task.cancel()
	if task.client != nil && task.client.IsConnected() {
		task.client.Disconnect(250)
	}
	rt := task.snapshot()
	rt.Status = "stopped"
	rt.Connected = false
	m.updateRuntime(id, rt)
	return m.store.SaveECounters(id, task.builder.Counters())
}

func (m *Manager) Delete(id string) error {
	if err := m.Stop(id); err != nil {
		return err
	}
	m.mu.Lock()
	delete(m.runtime, id)
	m.mu.Unlock()
	return m.store.DeleteTask(id)
}

func (m *Manager) updateRuntime(id string, runtime model.TaskRuntime) {
	m.mu.Lock()
	m.runtime[id] = runtime
	m.mu.Unlock()
	_ = m.store.SaveRuntime(id, runtime)
	m.broker.Publish(map[string]any{"taskId": id, "runtime": runtime})
}

func (t *runningTask) snapshot() model.TaskRuntime {
	rt := t.runtime
	rt.SentTotal = t.sentTotal.Load()
	rt.FailedTotal = t.failedTotal.Load()
	rt.SentLastSecond = t.lastSecond.Load()
	rt.SentLastMinute = t.lastMinute.Load()
	if total := t.sentTotal.Load(); total > 0 {
		rt.AveragePayload = t.payloadBytes.Load() / total
	}
	return rt
}

func (t *runningTask) connect() error {
	opts := mqtt.NewClientOptions()
	opts.AddBroker(t.config.BrokerURL)
	opts.SetClientID(resolveClientID(t.config))
	opts.SetCleanSession(true)
	opts.SetConnectRetry(true)
	opts.SetKeepAlive(30 * time.Second)
	opts.SetAutoReconnect(true)
	opts.SetConnectTimeout(10 * time.Second)
	if t.config.Username != "" {
		opts.SetUsername(t.config.Username)
		opts.SetPassword(t.config.Password)
	}

	if strings.HasPrefix(t.config.BrokerURL, "ssl://") || strings.HasPrefix(t.config.BrokerURL, "tls://") || strings.HasPrefix(t.config.BrokerURL, "mqtts://") {
		opts.SetTLSConfig(&tls.Config{InsecureSkipVerify: true})
	}

	client := mqtt.NewClient(opts)
	token := client.Connect()
	if ok := token.WaitTimeout(15 * time.Second); !ok {
		return fmt.Errorf("connect broker timeout")
	}
	if err := token.Error(); err != nil {
		return err
	}
	t.client = client
	return nil
}

func (t *runningTask) run(ctx context.Context, manager *Manager) {
	perSecond := float64(t.config.MessagesPerMinute) / 60.0
	if perSecond < 1 {
		perSecond = 1
	}

	workers := t.workerCount()
	jobs := make(chan int, workers*4)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case idx, ok := <-jobs:
					if !ok {
						return
					}
					t.publishDevice(idx, manager)
				}
			}
		}()
	}

	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	defer close(jobs)
	defer wg.Wait()

	var carry float64
	var index int
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			carry += perSecond
			n := int(carry)
			if n < 1 {
				continue
			}
			carry -= float64(n)
			for i := 0; i < n; i++ {
				deviceIndex := index % t.config.DeviceCount
				index++
				select {
				case jobs <- deviceIndex:
				default:
					t.failedTotal.Add(1)
					t.runtime.LastError = "worker queue full"
				}
			}
		}
	}
}

func (t *runningTask) publishDevice(idx int, manager *Manager) {
	topic, payload, err := t.builder.Build(idx)
	if err != nil {
		t.failedTotal.Add(1)
		t.runtime.LastError = err.Error()
		return
	}

	token := t.client.Publish(topic, 0, false, payload)
	token.Wait()
	if err = token.Error(); err != nil {
		t.failedTotal.Add(1)
		t.runtime.LastError = err.Error()
		t.runtime.Connected = t.client.IsConnected()
		manager.updateRuntime(t.config.ID, t.snapshot())
		return
	}

	now := time.Now()
	t.sentTotal.Add(1)
	t.lastSecond.Add(1)
	t.lastMinute.Add(1)
	t.payloadBytes.Add(int64(len(payload)))
	t.runtime.Connected = true
	t.runtime.LastPublishedAt = &now
}

func (t *runningTask) statsLoop(ctx context.Context, manager *Manager) {
	secondTicker := time.NewTicker(time.Second)
	minuteTicker := time.NewTicker(time.Minute)
	defer secondTicker.Stop()
	defer minuteTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			manager.updateRuntime(t.config.ID, t.snapshot())
			return
		case <-secondTicker.C:
			manager.updateRuntime(t.config.ID, t.snapshot())
			t.lastSecond.Store(0)
		case <-minuteTicker.C:
			t.lastMinute.Store(0)
		}
	}
}

func (t *runningTask) flushLoop(ctx context.Context, store *store.SQLiteStore) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			_ = store.SaveECounters(t.config.ID, t.builder.Counters())
			return
		case <-ticker.C:
			_ = store.SaveECounters(t.config.ID, t.builder.Counters())
		}
	}
}

func (t *runningTask) workerCount() int {
	switch {
	case t.config.MessagesPerMinute >= 60000:
		return 64
	case t.config.MessagesPerMinute >= 12000:
		return 32
	case t.config.MessagesPerMinute >= 1200:
		return 16
	default:
		return 8
	}
}

func resolveClientID(cfg model.TaskConfig) string {
	if strings.TrimSpace(cfg.ClientID) != "" {
		return cfg.ClientID
	}
	prefix := strings.TrimSpace(cfg.ClientIDPrefix)
	if prefix == "" {
		prefix = "mqtt-simulator"
	}
	return prefix + "-" + cfg.ID
}

func NormalizeBrokerURL(raw string) string {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err == nil && parsed.Scheme != "" {
		return raw
	}
	return "tcp://" + strings.TrimSpace(raw)
}
