package cn.daxpay.open.paylens.incidents.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompleteLinkGrouperTest {
    private static final Instant BASE = Instant.parse("2026-09-26T10:00:00Z");
    private static final GroupingPolicy POLICY = new GroupingPolicy(0.80, Duration.ofMinutes(10));

    @Test
    void mergesDifferentTradesOnlyWhenAllKnownBoundariesMatch() {
        Observation left = observation("a", "trade-a", BASE);
        Observation right = observation("b", "trade-b", BASE.plusSeconds(1));

        EventGroup group = grouper(Map.of("a|b", 0.91)).group(List.of(right, left), POLICY).groups().getFirst();

        assertEquals(List.of("a", "b"), ids(group));
        assertEquals(2, group.distinctTradeCount());
        assertEquals(0.91, group.minimumPairwiseSimilarity().orElseThrow());
    }

    @Test
    void neverMergesAcrossDifferentKnownBusinessBoundaries() {
        Observation baseline = observation("a", "trade-a", BASE);
        List<Observation> incompatible = List.of(
                with(baseline, "b", "trade-b", "merchant-2", "PAY", "AUTH", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "REFUND", "AUTH", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "PAY", "REFUND", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "PAY", "AUTH", "CH-2", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "PAY", "AUTH", "CH-1", "fail_paid", "E1"));

        for (Observation candidate : incompatible) {
            GroupingResult result = grouper(Map.of("a|b", 0.99)).group(List.of(baseline, candidate), POLICY);
            assertEquals(2, result.groups().size(), "unexpected merge for " + candidate);
        }
    }

    @Test
    void differentKnownErrorCodesPreventMergingEvenForTheSameTrade() {
        Observation left = observation("a", "trade-a", BASE);
        Observation right = with(left, "b", "trade-a", "merchant-1", "PAY", "AUTH", "CH-1", "close_paid", "E2");

        GroupingResult result = grouper(Map.of("a|b", 0.99)).group(List.of(left, right), POLICY);

        assertEquals(2, result.groups().size());
    }

    @Test
    void sameTradeDoesNotBypassKnownMerchantOrBusinessBoundaries() {
        Observation baseline = observation("a", "trade-a", BASE);
        List<Observation> incompatible = List.of(
                with(baseline, "b", "trade-a", "merchant-2", "PAY", "AUTH", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-a", "merchant-1", "REFUND", "AUTH", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-a", "merchant-1", "PAY", "QUERY", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-a", "merchant-1", "PAY", "AUTH", "CH-2", "close_paid", "E1"));

        for (Observation candidate : incompatible) {
            GroupingResult result = grouper(Map.of("a|b", 0.99)).group(List.of(baseline, candidate), POLICY);
            assertEquals(2, result.groups().size(), "same-trade merge crossed a known boundary: " + candidate);
        }
    }

    @Test
    void missingAnyCrossTradeBoundaryDisablesCrossTradeCandidates() {
        Observation baseline = observation("a", "trade-a", BASE);
        List<Observation> missing = List.of(
                with(baseline, "b", "trade-b", null, "PAY", "AUTH", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", " ", "AUTH", "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "PAY", null, "CH-1", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "PAY", "AUTH", "", "close_paid", "E1"),
                with(baseline, "b", "trade-b", "merchant-1", "PAY", "AUTH", "CH-1", null, "E1"));

        for (Observation candidate : missing) {
            GroupingResult result = grouper(Map.of("a|b", 0.99)).group(List.of(baseline, candidate), POLICY);
            assertEquals(2, result.groups().size(), "unknown boundary allowed merge for " + candidate);
        }
    }

    @Test
    void sameTradeObservationsCanMergeWithUnknownBoundariesAndCountOneTrade() {
        Observation left = with(observation("a", "trade-a", BASE),
                "a", "trade-a", null, "PAY", null, "CH-1", "close_paid", "E1");
        Observation right = with(observation("b", "trade-a", BASE.plusSeconds(1)),
                "b", "trade-a", null, "PAY", null, "CH-1", "close_paid", "E1");

        EventGroup group = grouper(Map.of("a|b", 0.87)).group(List.of(left, right), POLICY).groups().getFirst();

        assertEquals(2, group.observations().size());
        assertEquals(1, group.distinctTradeCount());
    }

    @Test
    void completeLinkPreventsChainMergingAndReportsMinimumPairwiseSimilarity() {
        Observation a = observation("a", "trade-a", BASE);
        Observation b = observation("b", "trade-b", BASE.plusSeconds(1));
        Observation c = observation("c", "trade-c", BASE.plusSeconds(2));
        Map<String, Double> scores = Map.of("a|b", 0.93, "a|c", 0.79, "b|c", 0.92);

        GroupingResult result = grouper(scores).group(List.of(c, b, a), POLICY);

        assertEquals(List.of(List.of("a", "b"), List.of("c")), result.groups().stream()
                .map(CompleteLinkGrouperTest::ids).toList());
        assertEquals(0.93, result.groups().getFirst().minimumPairwiseSimilarity().orElseThrow());
        assertTrue(result.groups().get(1).minimumPairwiseSimilarity().isEmpty());
        assertEquals(CompleteLinkGrouper.ALGORITHM_VERSION, result.algorithmVersion());
    }

    @Test
    void enforcesConfiguredWindowAndTenMinuteMaximum() {
        Observation a = observation("a", "trade-a", BASE);
        Observation exactlyTenMinutesLater = observation("b", "trade-b", BASE.plus(Duration.ofMinutes(10)));
        Observation justOutside = observation("c", "trade-c", BASE.plus(Duration.ofMinutes(10)).plusNanos(1));
        Observation threeMinutesLater = observation("d", "trade-d", BASE.plus(Duration.ofMinutes(3)));
        Map<String, Double> scores = Map.of("a|b", 0.90, "a|c", 0.90, "b|c", 0.90);

        GroupingResult within = grouper(scores).group(List.of(a, exactlyTenMinutesLater), POLICY);
        GroupingResult outside = grouper(scores).group(List.of(a, justOutside), POLICY);
        GroupingResult outsideCustomWindow = grouper(Map.of("a|d", 0.90)).group(
                List.of(a, threeMinutesLater), new GroupingPolicy(0.80, Duration.ofMinutes(2)));

        assertEquals(1, within.groups().size());
        assertEquals(2, outside.groups().size());
        assertEquals(2, outsideCustomWindow.groups().size());
    }

    @Test
    void duplicateIdenticalInputsAreDeduplicatedAndConflictingIdsAreRejected() {
        Observation observation = observation("a", "trade-a", BASE);
        EventGroup singleton = grouper(Map.of()).group(List.of(observation, observation), POLICY).groups().getFirst();
        Observation conflicting = observation("a", "trade-other", BASE);

        assertEquals(1, singleton.observations().size());
        assertEquals(1, singleton.distinctTradeCount());
        assertTrue(singleton.minimumPairwiseSimilarity().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> grouper(Map.of()).group(List.of(observation, conflicting), POLICY));
    }

    @Test
    void outputIsStableAcrossInputOrderAndResolvesEqualCandidatesByStableGroupOrder() {
        Observation a = observation("a", "trade-a", BASE);
        Observation b = observation("b", "trade-b", BASE.plusSeconds(1));
        Observation c = observation("c", "trade-c", BASE.plusSeconds(2));
        Observation d = observation("d", "trade-d", BASE.plusSeconds(3));
        Map<String, Double> scores = Map.of(
                "a|b", 0.95,
                "a|c", 0.90,
                "b|c", 0.70,
                "a|d", 0.90,
                "b|d", 0.90,
                "c|d", 0.90);

        List<List<String>> forward = grouper(scores).group(List.of(a, b, c, d), POLICY).groups().stream()
                .map(CompleteLinkGrouperTest::ids).toList();
        List<List<String>> reverse = grouper(scores).group(List.of(d, c, b, a), POLICY).groups().stream()
                .map(CompleteLinkGrouperTest::ids).toList();

        assertEquals(List.of(List.of("a", "b", "d"), List.of("c")), forward);
        assertEquals(forward, reverse);
        EventGroup joined = grouper(scores).group(List.of(a, b, c, d), POLICY).groups().getFirst();
        assertEquals(0.90, joined.minimumPairwiseSimilarity().orElseThrow());
    }

    @Test
    void rejectsNonFiniteSimilarityScores() {
        Observation a = observation("a", "trade-a", BASE);
        Observation b = observation("b", "trade-b", BASE.plusSeconds(1));

        assertThrows(IllegalArgumentException.class,
                () -> new CompleteLinkGrouper((left, right) -> Double.NaN).group(List.of(a, b), POLICY));
    }

    private static CompleteLinkGrouper grouper(Map<String, Double> pairScores) {
        Map<String, Double> immutableScores = Map.copyOf(pairScores);
        return new CompleteLinkGrouper((left, right) -> immutableScores.getOrDefault(pairKey(left, right), 0.0));
    }

    private static String pairKey(Observation left, Observation right) {
        return left.id().compareTo(right.id()) <= 0
                ? left.id() + "|" + right.id()
                : right.id() + "|" + left.id();
    }

    private static List<String> ids(EventGroup group) {
        return group.observations().stream().map(Observation::id).toList();
    }

    private static Observation observation(String id, String tradeNo, Instant time) {
        return new Observation(id, tradeNo, "merchant-1", "PAY", "AUTH", "CH-1", "close_paid", "E1", time, id);
    }

    private static Observation with(
            Observation original,
            String id,
            String tradeNo,
            String merchant,
            String businessType,
            String phase,
            String channel,
            String abnormalType,
            String errorCode) {
        return new Observation(id, tradeNo, merchant, businessType, phase, channel, abnormalType, errorCode,
                original.occurredAt(), id);
    }
}
