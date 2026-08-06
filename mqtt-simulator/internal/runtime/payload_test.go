package runtime

import (
	"encoding/json"
	"testing"
	"time"

	"mqtt-simulator/internal/model"
)

func TestPayloadBuilderEUsesCurrentTimestamp(t *testing.T) {
	builder := NewPayloadBuilder("task-1", model.TaskConfig{
		ProductID:     "prod-1",
		MessageType:   "change",
		TopicTemplate: "IOT/{productId}/Data",
		Metadata: []model.Property{
			{ID: "E", Name: "电能", ValueType: model.PropertyType{Type: "float", Scale: 3}},
		},
	}, nil)
	builder.now = func() time.Time {
		return time.UnixMilli(1700000012345)
	}

	_, raw, err := builder.Build(0)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}

	value := extractValue(t, raw)
	if value != "1700000.01" {
		t.Fatalf("expected E to use timestamp value, got %s", value)
	}
}

func TestPayloadBuilderTopicTemplate(t *testing.T) {
	builder := NewPayloadBuilder("task-2", model.TaskConfig{
		ProductID:     "prod-2",
		MessageType:   "custom-type",
		TopicTemplate: "IOT/{productId}/Data",
		Metadata: []model.Property{
			{ID: "Ua", Name: "A相电压", ValueType: model.PropertyType{Type: "float", Scale: 3}},
		},
	}, nil)

	topic, payload, err := builder.Build(0)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}
	if topic != "IOT/prod-2/Data" {
		t.Fatalf("unexpected topic: %s", topic)
	}

	var body map[string]any
	if err = json.Unmarshal(payload, &body); err != nil {
		t.Fatalf("unmarshal payload: %v", err)
	}
	if body["type"] != "custom-type" {
		t.Fatalf("unexpected type: %v", body["type"])
	}
}

func extractValue(t *testing.T, payload []byte) string {
	t.Helper()
	var body struct {
		Data map[string][]struct {
			Value string `json:"value"`
		} `json:"data"`
	}
	if err := json.Unmarshal(payload, &body); err != nil {
		t.Fatalf("unmarshal payload: %v", err)
	}
	for _, values := range body.Data {
		if len(values) == 0 {
			t.Fatal("payload data array is empty")
		}
		return values[0].Value
	}
	t.Fatal("payload data missing device")
	return ""
}
