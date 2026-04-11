package runtime

import (
	"encoding/json"
	"fmt"
	"math"
	"math/rand"
	"strconv"
	"strings"
	"sync"
	"time"

	"mqtt-simulator/internal/model"
)

type PayloadBuilder struct {
	taskID    string
	config    model.TaskConfig
	mu        sync.RWMutex
	eCounters map[string]float64
	random    *rand.Rand
	randMu    sync.Mutex
	zeroMu    sync.Mutex
	zeroStreak int
}

func NewPayloadBuilder(taskID string, config model.TaskConfig, counters map[string]float64) *PayloadBuilder {
	if counters == nil {
		counters = map[string]float64{}
	}
	return &PayloadBuilder{
		taskID:    taskID,
		config:    config,
		eCounters: counters,
		random:    rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (b *PayloadBuilder) Counters() map[string]float64 {
	b.mu.RLock()
	defer b.mu.RUnlock()
	out := make(map[string]float64, len(b.eCounters))
	for k, v := range b.eCounters {
		out[k] = v
	}
	return out
}

func (b *PayloadBuilder) DeviceID(index int) string {
	return fmt.Sprintf("%06d", index+1)
}

func (b *PayloadBuilder) Topic() string {
	topic := strings.TrimSpace(b.config.TopicTemplate)
	if topic == "" {
		topic = "IOT/{productId}/Data"
	}
	return strings.ReplaceAll(topic, "{productId}", b.config.ProductID)
}

func (b *PayloadBuilder) Build(index int) (string, []byte, error) {
	deviceID := b.DeviceID(index)
	items := make([]map[string]any, 0, len(b.config.Metadata))
	for _, property := range b.config.Metadata {
		items = append(items, map[string]any{
			"id":   property.ID,
			"desc": property.Name,
			"value": b.valueFor(deviceID, property),
		})
	}

	body := map[string]any{
		"type": nonEmpty(b.config.MessageType, "change"),
		"sn":   b.config.ProductID,
		"time": time.Now().Format("2006-01-02 15:04:05"),
		"data": map[string]any{
			deviceID: items,
		},
	}

	raw, err := json.Marshal(body)
	if err != nil {
		return "", nil, err
	}
	return b.Topic(), raw, nil
}

func (b *PayloadBuilder) valueFor(deviceID string, property model.Property) any {
	if property.ID == "E" {
		b.mu.Lock()
		defer b.mu.Unlock()
		last := b.eCounters[deviceID]
		next := round(last+0.001+b.randFloat64()*0.199, 3)
		b.eCounters[deviceID] = next
		return fmt.Sprintf("%.3f", next)
	}

	switch strings.ToLower(property.ValueType.Type) {
	case "float":
		scale := property.ValueType.Scale
		if scale <= 0 {
			scale = 3
		}
		value := b.randFloat64() * 1000
		b.bumpZeroGuard(value)
		return fmt.Sprintf("%.*f", scale, value)
	case "enum":
		if len(property.ValueType.Elements) > 0 {
			if property.ID == "_dev_status" {
				if b.randFloat64() < 0.95 {
					b.bumpZeroGuard(1)
					return property.ValueType.Elements[0].Value
				}
				b.bumpZeroGuard(1)
				return property.ValueType.Elements[len(property.ValueType.Elements)-1].Value
			}
			b.bumpZeroGuard(1)
			return property.ValueType.Elements[b.randIntn(len(property.ValueType.Elements))].Value
		}
		b.bumpZeroGuard(0)
		return "0"
	case "string":
		b.bumpZeroGuard(1)
		return fmt.Sprintf("%s_%04d", property.ID, b.randIntn(10000))
	default:
		value := round(b.randFloat64()*1000, 3)
		b.bumpZeroGuard(value)
		return strconv.FormatFloat(value, 'f', 3, 64)
	}
}

func (b *PayloadBuilder) randFloat64() float64 {
	b.randMu.Lock()
	defer b.randMu.Unlock()
	return b.random.Float64()
}

func (b *PayloadBuilder) randIntn(n int) int {
	b.randMu.Lock()
	defer b.randMu.Unlock()
	return b.random.Intn(n)
}

// bumpZeroGuard watches for improbable runs of zero-ish values and reseeds RNG.
func (b *PayloadBuilder) bumpZeroGuard(value float64) {
	b.zeroMu.Lock()
	defer b.zeroMu.Unlock()

	if math.Abs(value) < 1e-9 {
		b.zeroStreak++
	} else {
		b.zeroStreak = 0
	}

	if b.zeroStreak >= 50 {
		b.randMu.Lock()
		b.random.Seed(time.Now().UnixNano())
		b.randMu.Unlock()
		b.zeroStreak = 0
	}
}

func round(v float64, scale int) float64 {
	pow := math.Pow10(scale)
	return math.Round(v*pow) / pow
}

func nonEmpty(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}
