package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.incidents.domain.Observation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayLensMergeSuggestionServiceTest {
    private static Observation obs(String id, String merchant, String phase, String code, int minute) {
        return new Observation(id, id, merchant, "PAY", phase, "SIM", "QUERY_ERROR", code,
                Instant.parse("2026-09-26T10:00:00Z").plusSeconds(60L * minute), "脱敏后的查单描述");
    }

    @Test
    void requiresKnownMatchingBoundariesAndCombinedTenMinuteWindow() {
        var source = List.of(obs("a", "M1", "QUERY", "TIMEOUT", 0));
        assertTrue(PayLensMergeSuggestionService.compatible(source,
                List.of(obs("b", "M1", "QUERY", "TIMEOUT", 8))));
        assertFalse(PayLensMergeSuggestionService.compatible(source,
                List.of(obs("b", "M2", "QUERY", "TIMEOUT", 8))));
        assertFalse(PayLensMergeSuggestionService.compatible(source,
                List.of(obs("b", "M1", "CALLBACK", "TIMEOUT", 8))));
        assertFalse(PayLensMergeSuggestionService.compatible(source,
                List.of(obs("b", "M1", "QUERY", "REFUSED", 8))));
        assertFalse(PayLensMergeSuggestionService.compatible(source,
                List.of(obs("b", "M1", "QUERY", "TIMEOUT", 11))));
        assertFalse(PayLensMergeSuggestionService.compatible(
                List.of(obs("a", null, "QUERY", "TIMEOUT", 0)),
                List.of(obs("b", null, "QUERY", "TIMEOUT", 8))));
    }
}
