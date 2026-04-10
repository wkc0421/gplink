package logging

import (
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/natefinch/lumberjack.v2"
)

const (
	defaultDataDir = "mqtt-simulator-data"
	defaultLogFile = "mqtt-simulator.log"
)

func Setup() error {
	logDir := filepath.Join(resolveDataDir(), "logs")
	if env := strings.TrimSpace(os.Getenv("MQTT_SIMULATOR_LOG_DIR")); env != "" {
		logDir = env
	}
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		return err
	}

	logFile := filepath.Join(logDir, defaultLogFile)
	rotateWriter := &lumberjack.Logger{
		Filename:   logFile,
		MaxSize:    10,
		MaxBackups: 5,
		MaxAge:     14,
		Compress:   false,
	}

	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)
	log.SetOutput(io.MultiWriter(os.Stdout, rotateWriter))
	log.Printf("logging initialized logFile=%s", logFile)
	return nil
}

func resolveDataDir() string {
	if env := strings.TrimSpace(os.Getenv("MQTT_SIMULATOR_DATA_DIR")); env != "" {
		return env
	}
	return defaultDataDir
}
