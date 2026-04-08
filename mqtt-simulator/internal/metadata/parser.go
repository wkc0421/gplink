package metadata

import (
	"encoding/json"
	"fmt"
	"strings"

	"mqtt-simulator/internal/model"
)

type rawMetadata struct {
	Properties []model.Property `json:"properties"`
}

func Parse(raw string) ([]model.Property, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, fmt.Errorf("metadata is empty")
	}

	var parsed rawMetadata
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return nil, fmt.Errorf("invalid metadata json: %w", err)
	}

	if len(parsed.Properties) == 0 {
		return nil, fmt.Errorf("metadata.properties is empty")
	}

	out := make([]model.Property, 0, len(parsed.Properties))
	for _, item := range parsed.Properties {
		if strings.TrimSpace(item.ID) == "" || strings.TrimSpace(item.Name) == "" {
			continue
		}
		out = append(out, item)
	}

	if len(out) == 0 {
		return nil, fmt.Errorf("no valid properties found in metadata")
	}

	return out, nil
}
