package cn.daxpay.open.paylens.incidents.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** Policy parameters for deterministic complete-link grouping. */
public record GroupingPolicy(double threshold, Duration timeWindow) {
    public static final double MIN_THRESHOLD = 0.80;
    public static final double MAX_THRESHOLD = 0.98;
    public static final double THRESHOLD_STEP = 0.02;
    public static final Duration MAX_TIME_WINDOW = Duration.ofMinutes(10);

    public GroupingPolicy {
        if (!Double.isFinite(threshold)
                || threshold < MIN_THRESHOLD
                || threshold > MAX_THRESHOLD
                || !isSupportedThreshold(threshold)) {
            throw new IllegalArgumentException("threshold must be from 0.80 to 0.98 in steps of 0.02");
        }
        Objects.requireNonNull(timeWindow, "timeWindow");
        if (timeWindow.isZero() || timeWindow.isNegative() || timeWindow.compareTo(MAX_TIME_WINDOW) > 0) {
            throw new IllegalArgumentException("timeWindow must be positive and at most 10 minutes");
        }
    }

    private static boolean isSupportedThreshold(double threshold) {
        BigDecimal offset = BigDecimal.valueOf(threshold).subtract(BigDecimal.valueOf(MIN_THRESHOLD));
        return offset.remainder(BigDecimal.valueOf(THRESHOLD_STEP)).compareTo(BigDecimal.ZERO) == 0;
    }
}
