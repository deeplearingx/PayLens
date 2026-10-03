package cn.daxpay.open.paylens.incidents.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupingPolicyTest {

    @Test
    void acceptsOnlySupportedThresholdStepsAndAtMostTenMinutes() {
        assertDoesNotThrow(() -> new GroupingPolicy(0.80, Duration.ofMinutes(10)));
        assertDoesNotThrow(() -> new GroupingPolicy(0.84, Duration.ofSeconds(30)));
        assertDoesNotThrow(() -> new GroupingPolicy(0.98, Duration.ofNanos(1)));

        assertThrows(IllegalArgumentException.class, () -> new GroupingPolicy(0.79, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPolicy(0.81, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPolicy(0.99, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPolicy(Double.NaN, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new GroupingPolicy(0.90, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new GroupingPolicy(0.90, Duration.ofMinutes(10).plusNanos(1)));
    }
}
