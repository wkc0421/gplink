package runtime

import "testing"

func TestMessagesDueForSecondHonorsLowRates(t *testing.T) {
	var carry int
	total := 0
	for i := 0; i < 59; i++ {
		if due := messagesDueForSecond(1, &carry); due != 0 {
			t.Fatalf("expected no messages before one minute, got %d at second %d", due, i+1)
		}
	}
	total += messagesDueForSecond(1, &carry)
	if total != 1 {
		t.Fatalf("expected exactly 1 message after one minute, got %d", total)
	}
}

func TestMessagesDueForSecondKeepsMinuteTotals(t *testing.T) {
	tests := []int{30, 59, 60, 61, 120, 600}
	for _, rate := range tests {
		t.Run("rate", func(t *testing.T) {
			var carry int
			total := 0
			for i := 0; i < 60; i++ {
				total += messagesDueForSecond(rate, &carry)
			}
			if total != rate {
				t.Fatalf("expected %d messages in one minute, got %d", rate, total)
			}
		})
	}
}
