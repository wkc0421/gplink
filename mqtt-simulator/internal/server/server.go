package server

import (
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"mqtt-simulator/internal/metadata"
	"mqtt-simulator/internal/model"
	"mqtt-simulator/internal/runtime"
	"mqtt-simulator/internal/store"
)

//go:embed web/*
var webAssets embed.FS

type Server struct {
	engine  *gin.Engine
	store   *store.SQLiteStore
	runtime *runtime.Manager
	broker  *runtime.Broker
}

func New() (*Server, error) {
	dataDir := filepath.Join("mqtt-simulator-data")
	if env := strings.TrimSpace(os.Getenv("MQTT_SIMULATOR_DATA_DIR")); env != "" {
		dataDir = env
	}

	dbStore, err := store.NewSQLite(filepath.Join(dataDir, "simulator.db"))
	if err != nil {
		return nil, err
	}

	broker := runtime.NewBroker()
	manager, err := runtime.NewManager(dbStore, broker)
	if err != nil {
		return nil, err
	}

	gin.SetMode(gin.ReleaseMode)
	engine := gin.Default()

	srv := &Server{
		engine:  engine,
		store:   dbStore,
		runtime: manager,
		broker:  broker,
	}
	srv.routes()
	return srv, nil
}

func (s *Server) Run() error {
	addr := ":8099"
	if env := strings.TrimSpace(os.Getenv("MQTT_SIMULATOR_ADDR")); env != "" {
		addr = env
	}
	return s.engine.Run(addr)
}

func (s *Server) routes() {
	api := s.engine.Group("/api")
	{
		api.POST("/products/import-model", s.importModel)
		api.GET("/tasks", s.listTasks)
		api.POST("/tasks", s.createTask)
		api.PUT("/tasks/:id", s.updateTask)
		api.DELETE("/tasks/:id", s.deleteTask)
		api.POST("/tasks/:id/start", s.startTask)
		api.POST("/tasks/:id/stop", s.stopTask)
		api.GET("/tasks/:id/stats", s.taskStats)
		api.POST("/preview/payload", s.previewPayload)
		api.GET("/stream/tasks", gin.WrapF(s.broker.Subscribe))
	}

	fileSystem := s.staticFS()
	s.engine.StaticFileFS("/assets/styles.css", "styles.css", fileSystem)
	s.engine.StaticFileFS("/assets/app.js", "app.js", fileSystem)
	s.engine.GET("/", func(c *gin.Context) {
		content, err := fs.ReadFile(s.staticAssetFS(), "index.html")
		if err != nil {
			c.String(http.StatusInternalServerError, "load index.html failed: %v", err)
			return
		}
		c.Data(http.StatusOK, "text/html; charset=utf-8", content)
	})
}

func (s *Server) importModel(c *gin.Context) {
	var req model.MetadataImportRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	properties, err := metadata.Parse(req.Raw)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, model.MetadataImportResponse{Properties: properties})
}

func (s *Server) listTasks(c *gin.Context) {
	configs, err := s.store.ListTasks()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": s.runtime.List(configs)})
}

func (s *Server) createTask(c *gin.Context) {
	var config model.TaskConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	config.ID = uuid.NewString()
	s.normalizeConfig(&config)
	if err := validateTask(config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	now := time.Now()
	config.CreatedAt = now
	config.UpdatedAt = now
	if err := s.store.UpsertTask(config); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"item": model.TaskSnapshot{ID: config.ID, Config: config, Runtime: model.TaskRuntime{Status: "idle"}}})
}

func (s *Server) updateTask(c *gin.Context) {
	id := c.Param("id")
	oldConfig, err := s.store.GetTask(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}

	var config model.TaskConfig
	if err = c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	config.ID = id
	config.CreatedAt = oldConfig.CreatedAt
	config.UpdatedAt = time.Now()
	s.normalizeConfig(&config)
	if err = validateTask(config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err = s.runtime.Stop(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if err = s.store.UpsertTask(config); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"item": model.TaskSnapshot{ID: config.ID, Config: config, Runtime: s.runtime.GetRuntime(id)}})
}

func (s *Server) deleteTask(c *gin.Context) {
	if err := s.runtime.Delete(c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) startTask(c *gin.Context) {
	config, err := s.store.GetTask(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}
	if err = s.runtime.Start(config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"runtime": s.runtime.GetRuntime(config.ID)})
}

func (s *Server) stopTask(c *gin.Context) {
	id := c.Param("id")
	if err := s.runtime.Stop(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"runtime": s.runtime.GetRuntime(id)})
}

func (s *Server) taskStats(c *gin.Context) {
	id := c.Param("id")
	config, err := s.store.GetTask(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"item": model.TaskSnapshot{
			ID:      id,
			Config:  config,
			Runtime: s.runtime.GetRuntime(id),
		},
	})
}

func (s *Server) previewPayload(c *gin.Context) {
	var req model.PreviewRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	s.normalizeConfig(&req.Config)
	if err := validateTask(req.Config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	builder := runtime.NewPayloadBuilder("preview", req.Config, nil)
	topic, payload, err := builder.Build(0)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, model.PreviewResponse{Topic: topic, Payload: string(payload)})
}

func (s *Server) normalizeConfig(config *model.TaskConfig) {
	config.Name = strings.TrimSpace(config.Name)
	config.MessageType = strings.TrimSpace(config.MessageType)
	if config.MessageType == "" {
		config.MessageType = "change"
	}
	config.ProductID = strings.TrimSpace(config.ProductID)
	config.BrokerURL = runtime.NormalizeBrokerURL(config.BrokerURL)
	config.TopicTemplate = strings.TrimSpace(config.TopicTemplate)
	if config.TopicTemplate == "" {
		config.TopicTemplate = "IOT/{productId}/Data"
	}
}

func validateTask(config model.TaskConfig) error {
	switch {
	case strings.TrimSpace(config.Name) == "":
		return errors.New("name is required")
	case strings.TrimSpace(config.ProductID) == "":
		return errors.New("productId is required")
	case len(config.Metadata) == 0:
		return errors.New("metadata is required")
	case config.DeviceCount <= 0:
		return errors.New("deviceCount must be greater than 0")
	case config.MessagesPerMinute <= 0:
		return errors.New("messagesPerMinute must be greater than 0")
	case strings.TrimSpace(config.BrokerURL) == "":
		return errors.New("brokerUrl is required")
	}
	return nil
}

func (s *Server) staticFS() http.FileSystem {
	if _, err := os.Stat("web/index.html"); err == nil {
		return http.Dir("web")
	}
	sub, err := fs.Sub(webAssets, "web")
	if err != nil {
		return http.FS(webAssets)
	}
	return http.FS(sub)
}

func (s *Server) staticAssetFS() fs.FS {
	if _, err := os.Stat("web/index.html"); err == nil {
		return os.DirFS("web")
	}
	sub, err := fs.Sub(webAssets, "web")
	if err != nil {
		panic(fmt.Sprintf("sub web assets: %v", err))
	}
	return sub
}
