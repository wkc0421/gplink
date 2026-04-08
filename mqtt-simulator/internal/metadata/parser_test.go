package metadata

import "testing"

func TestParse(t *testing.T) {
	raw := `{
		"properties": [
			{
				"id": "Ua",
				"name": "A相电压",
				"valueType": { "type": "float", "scale": 3 }
			},
			{
				"id": "E",
				"name": "组合有功总电能",
				"valueType": { "type": "float", "scale": 3 }
			}
		]
	}`

	properties, err := Parse(raw)
	if err != nil {
		t.Fatalf("parse metadata: %v", err)
	}

	if got := len(properties); got != 2 {
		t.Fatalf("expected 2 properties, got %d", got)
	}
	if properties[0].ID != "Ua" || properties[1].ID != "E" {
		t.Fatalf("unexpected property ids: %+v", properties)
	}
}
