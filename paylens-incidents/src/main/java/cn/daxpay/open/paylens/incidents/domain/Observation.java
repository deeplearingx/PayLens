package cn.daxpay.open.paylens.incidents.domain;

import java.time.Instant;
import java.util.Objects;

/** A caller-sanitized, immutable observation of one payment anomaly. This type does not sanitize input. */
public record Observation(
        String id,
        String tradeNo,
        String mchNo,
        String businessType,
        String phase,
        String channel,
        String abnormalType,
        String errorCode,
        Instant occurredAt,
        String normalizedDescription) {

    public Observation {
        requireNonBlank(id, "id");
        requireNonBlank(tradeNo, "tradeNo");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
