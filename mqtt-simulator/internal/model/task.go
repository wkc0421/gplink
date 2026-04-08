package model

import "time"

type MetadataImportRequest struct {
	Raw string `json:"raw"`
}

type MetadataImportResponse struct {
	Properties []Property `json:"properties"`
}

type PreviewRequest struct {
	Config TaskConfig `json:"config"`
}

type PreviewResponse struct {
	Topic   string `json:"topic"`
	Payload string `json:"payload"`
}

type TaskConfig struct {
	ID                string     `json:"id"`
	Name              string     `json:"name"`
	ProductID         string     `json:"productId"`
	MessageType       string     `json:"messageType"`
	Metadata          []Property `json:"metadata"`
	DeviceCount       int        `json:"deviceCount"`
	MessagesPerMinute int        `json:"messagesPerMinute"`
	BrokerURL         string     `json:"brokerUrl"`
	Username          string     `json:"username"`
	Password          string     `json:"password"`
	ClientID          string     `json:"clientId"`
	ClientIDPrefix    string     `json:"clientIdPrefix"`
	TopicTemplate     string     `json:"topicTemplate"`
	CreatedAt         time.Time  `json:"createdAt"`
	UpdatedAt         time.Time  `json:"updatedAt"`
}

type Property struct {
	ID        string         `json:"id"`
	Name      string         `json:"name"`
	ValueType PropertyType   `json:"valueType"`
}

type PropertyType struct {
	ID       string        `json:"id"`
	Type     string        `json:"type"`
	Scale    int           `json:"scale"`
	Elements []EnumElement `json:"elements"`
}

type EnumElement struct {
	Value string `json:"value"`
	Text  string `json:"text"`
}

type Task struct {
	Config  TaskConfig  `json:"config"`
	Runtime TaskRuntime `json:"runtime"`
}

type TaskRuntime struct {
	Status          string     `json:"status"`
	Connected       bool       `json:"connected"`
	SentTotal       int64      `json:"sentTotal"`
	FailedTotal     int64      `json:"failedTotal"`
	SentLastMinute  int64      `json:"sentLastMinute"`
	SentLastSecond  int64      `json:"sentLastSecond"`
	AveragePayload  int64      `json:"averagePayload"`
	LastError       string     `json:"lastError"`
	StartedAt       *time.Time `json:"startedAt"`
	LastPublishedAt *time.Time `json:"lastPublishedAt"`
}

type TaskSnapshot struct {
	ID      string      `json:"id"`
	Config  TaskConfig  `json:"config"`
	Runtime TaskRuntime `json:"runtime"`
}
