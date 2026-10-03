package cn.daxpay.open.paylens.incidents.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupingThresholdCalibratorTest {
    @Test
    void selectsHighestRecallThresholdMeetingPrecisionAndImprovingRuleBaseline() {
        GroupingThresholdCalibrator.Result result = GroupingThresholdCalibrator.calibrate(List.of(
                new GroupingThresholdCalibrator.Pair(0.97, true, false),
                new GroupingThresholdCalibrator.Pair(0.93, true, false),
                new GroupingThresholdCalibrator.Pair(0.89, true, false),
                new GroupingThresholdCalibrator.Pair(0.95, false, false),
                new GroupingThresholdCalibrator.Pair(0.90, false, false),
                new GroupingThresholdCalibrator.Pair(0.86, false, false)));

        assertEquals(10, result.curve().size());
        assertEquals(0.0, result.ruleRecall());
        assertTrue(result.canEnableSemanticAutoMerge());
        assertEquals(0.96, result.selected().orElseThrow().threshold());
        assertEquals(1.0, result.selected().orElseThrow().precision());
        assertEquals(1.0 / 3.0, result.selected().orElseThrow().recall());
        assertEquals("CALIBRATED", result.decision());
    }

    @Test
    void leavesSemanticMergeDisabledWhenNoThresholdMeetsPrecisionGate() {
        GroupingThresholdCalibrator.Result result = GroupingThresholdCalibrator.calibrate(List.of(
                new GroupingThresholdCalibrator.Pair(0.85, true, false),
                new GroupingThresholdCalibrator.Pair(0.97, false, false)));

        assertFalse(result.canEnableSemanticAutoMerge());
        assertEquals("NO_THRESHOLD_MET_PRECISION_AND_RULE_RECALL_GATE", result.decision());
    }

    @Test
    void rejectsInvalidEvaluationInputs() {
        assertThrows(IllegalArgumentException.class, () -> GroupingThresholdCalibrator.calibrate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> GroupingThresholdCalibrator.calibrate(List.of(
                new GroupingThresholdCalibrator.Pair(Double.NaN, true, false))));
    }
}
