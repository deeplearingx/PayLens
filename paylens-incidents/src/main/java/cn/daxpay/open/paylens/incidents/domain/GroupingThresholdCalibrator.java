package cn.daxpay.open.paylens.incidents.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Selects a semantic grouping threshold without silently weakening the rule baseline. */
public final class GroupingThresholdCalibrator {
    public static final double DEFAULT_MIN_PRECISION = 0.95;

    private GroupingThresholdCalibrator() { }

    /** A labelled, hard-boundary-compatible pair from a frozen evaluation split. */
    public record Pair(double similarity, boolean sameIncident, boolean ruleWouldGroup) {
        public Pair {
            if (!Double.isFinite(similarity) || similarity < 0.0 || similarity > 1.0) {
                throw new IllegalArgumentException("similarity must be between 0 and 1");
            }
        }
    }

    public record Metrics(double threshold, long truePositive, long falsePositive,
                          long falseNegative, long trueNegative) {
        public double precision() {
            long predicted = truePositive + falsePositive;
            return predicted == 0 ? 0.0 : (double) truePositive / predicted;
        }

        public double recall() {
            long actual = truePositive + falseNegative;
            return actual == 0 ? 0.0 : (double) truePositive / actual;
        }
    }

    public record Result(List<Metrics> curve, double ruleRecall, Optional<Metrics> selected,
                         String decision) {
        public Result {
            curve = List.copyOf(curve);
            selected = Objects.requireNonNull(selected, "selected");
            Objects.requireNonNull(decision, "decision");
        }

        public boolean canEnableSemanticAutoMerge() {
            return selected.isPresent();
        }
    }

    public static Result calibrate(List<Pair> pairs) {
        return calibrate(pairs, DEFAULT_MIN_PRECISION);
    }

    public static Result calibrate(List<Pair> pairs, double minimumPrecision) {
        Objects.requireNonNull(pairs, "pairs");
        if (pairs.isEmpty()) throw new IllegalArgumentException("at least one labelled pair is required");
        if (!Double.isFinite(minimumPrecision) || minimumPrecision <= 0 || minimumPrecision > 1) {
            throw new IllegalArgumentException("minimumPrecision must be in (0, 1]");
        }

        long positivePairs = pairs.stream().filter(Pair::sameIncident).count();
        if (positivePairs == 0) throw new IllegalArgumentException("evaluation pairs must include a positive pair");
        long ruleTruePositives = pairs.stream().filter(pair -> pair.sameIncident() && pair.ruleWouldGroup()).count();
        double ruleRecall = (double) ruleTruePositives / positivePairs;

        List<Metrics> curve = new ArrayList<>();
        for (int step = 0; step <= 9; step++) {
            double threshold = BigDecimal.valueOf(GroupingPolicy.MIN_THRESHOLD)
                    .add(BigDecimal.valueOf(GroupingPolicy.THRESHOLD_STEP).multiply(BigDecimal.valueOf(step)))
                    .doubleValue();
            long tp = 0, fp = 0, fn = 0, tn = 0;
            for (Pair pair : pairs) {
                boolean predictedMerge = pair.similarity() >= threshold;
                if (predictedMerge && pair.sameIncident()) tp++;
                else if (predictedMerge) fp++;
                else if (pair.sameIncident()) fn++;
                else tn++;
            }
            curve.add(new Metrics(threshold, tp, fp, fn, tn));
        }

        Optional<Metrics> selected = curve.stream()
                .filter(metrics -> metrics.precision() >= minimumPrecision)
                .filter(metrics -> metrics.recall() > ruleRecall)
                .max(Comparator.comparingDouble(Metrics::recall)
                        .thenComparingDouble(Metrics::threshold));
        String decision = selected.isPresent() ? "CALIBRATED" : "NO_THRESHOLD_MET_PRECISION_AND_RULE_RECALL_GATE";
        return new Result(curve, ruleRecall, selected, decision);
    }
}
