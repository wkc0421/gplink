package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"

	"mqtt-simulator/internal/model"
)

type SQLiteStore struct {
	db *sql.DB
}

func NewSQLite(path string) (*SQLiteStore, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}

	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	store := &SQLiteStore{db: db}
	if err = store.migrate(); err != nil {
		return nil, err
	}

	return store, nil
}

func (s *SQLiteStore) migrate() error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS tasks (
			id TEXT PRIMARY KEY,
			config_json TEXT NOT NULL,
			created_at DATETIME NOT NULL,
			updated_at DATETIME NOT NULL
		);`,
		`CREATE TABLE IF NOT EXISTS task_runtime (
			task_id TEXT PRIMARY KEY,
			runtime_json TEXT NOT NULL,
			updated_at DATETIME NOT NULL
		);`,
		`CREATE TABLE IF NOT EXISTS e_counters (
			task_id TEXT NOT NULL,
			device_id TEXT NOT NULL,
			value REAL NOT NULL,
			updated_at DATETIME NOT NULL,
			PRIMARY KEY(task_id, device_id)
		);`,
	}
	for _, stmt := range stmts {
		if _, err := s.db.Exec(stmt); err != nil {
			return err
		}
	}
	return nil
}

func (s *SQLiteStore) Close() error {
	return s.db.Close()
}

func (s *SQLiteStore) UpsertTask(config model.TaskConfig) error {
	payload, err := json.Marshal(config)
	if err != nil {
		return err
	}

	now := time.Now()
	if config.CreatedAt.IsZero() {
		config.CreatedAt = now
	}
	config.UpdatedAt = now
	payload, err = json.Marshal(config)
	if err != nil {
		return err
	}

	_, err = s.db.Exec(`
		INSERT INTO tasks(id, config_json, created_at, updated_at)
		VALUES(?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			config_json = excluded.config_json,
			updated_at = excluded.updated_at
	`, config.ID, string(payload), config.CreatedAt, config.UpdatedAt)

	return err
}

func (s *SQLiteStore) DeleteTask(id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, stmt := range []string{
		`DELETE FROM tasks WHERE id = ?`,
		`DELETE FROM task_runtime WHERE task_id = ?`,
		`DELETE FROM e_counters WHERE task_id = ?`,
	} {
		if _, err = tx.Exec(stmt, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *SQLiteStore) ListTasks() ([]model.TaskConfig, error) {
	rows, err := s.db.Query(`SELECT config_json FROM tasks ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []model.TaskConfig
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			return nil, err
		}
		var config model.TaskConfig
		if err = json.Unmarshal([]byte(raw), &config); err != nil {
			return nil, err
		}
		out = append(out, config)
	}
	return out, rows.Err()
}

func (s *SQLiteStore) GetTask(id string) (model.TaskConfig, error) {
	var raw string
	if err := s.db.QueryRow(`SELECT config_json FROM tasks WHERE id = ?`, id).Scan(&raw); err != nil {
		return model.TaskConfig{}, err
	}

	var config model.TaskConfig
	if err := json.Unmarshal([]byte(raw), &config); err != nil {
		return model.TaskConfig{}, err
	}
	return config, nil
}

func (s *SQLiteStore) SaveRuntime(id string, runtime model.TaskRuntime) error {
	payload, err := json.Marshal(runtime)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`
		INSERT INTO task_runtime(task_id, runtime_json, updated_at)
		VALUES(?, ?, ?)
		ON CONFLICT(task_id) DO UPDATE SET
			runtime_json = excluded.runtime_json,
			updated_at = excluded.updated_at
	`, id, string(payload), time.Now())
	return err
}

func (s *SQLiteStore) LoadRuntimes() (map[string]model.TaskRuntime, error) {
	rows, err := s.db.Query(`SELECT task_id, runtime_json FROM task_runtime`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := map[string]model.TaskRuntime{}
	for rows.Next() {
		var id string
		var raw string
		if err = rows.Scan(&id, &raw); err != nil {
			return nil, err
		}
		var runtime model.TaskRuntime
		if err = json.Unmarshal([]byte(raw), &runtime); err != nil {
			return nil, err
		}
		out[id] = runtime
	}
	return out, rows.Err()
}

func (s *SQLiteStore) LoadECounters(taskID string) (map[string]float64, error) {
	rows, err := s.db.Query(`SELECT device_id, value FROM e_counters WHERE task_id = ?`, taskID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := map[string]float64{}
	for rows.Next() {
		var deviceID string
		var value float64
		if err = rows.Scan(&deviceID, &value); err != nil {
			return nil, err
		}
		out[deviceID] = value
	}
	return out, rows.Err()
}

func (s *SQLiteStore) SaveECounters(taskID string, counters map[string]float64) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err = tx.Exec(`DELETE FROM e_counters WHERE task_id = ?`, taskID); err != nil {
		return err
	}

	stmt, err := tx.Prepare(`INSERT INTO e_counters(task_id, device_id, value, updated_at) VALUES(?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	now := time.Now()
	for deviceID, value := range counters {
		if _, err = stmt.Exec(taskID, deviceID, value, now); err != nil {
			return fmt.Errorf("save e counter %s: %w", deviceID, err)
		}
	}
	return tx.Commit()
}
