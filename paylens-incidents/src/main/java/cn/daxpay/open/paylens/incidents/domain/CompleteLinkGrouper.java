package cn.daxpay.open.paylens.incidents.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic greedy complete-link grouping. Every observation added to a group must satisfy
 * all boundary, time-window, and similarity checks against every existing member.
 */
public final class CompleteLinkGrouper implements Grouper {
    public static final String ALGORITHM_VERSION = "complete-link-v1";

    private static final Comparator<String> NULLABLE_STRING_ORDER = Comparator.nullsLast(String::compareTo);
    private static final Comparator<Observation> OBSERVATION_ORDER = Comparator
            .comparing(Observation::occurredAt)
            .thenComparing(Observation::id)
            .thenComparing(Observation::tradeNo)
            .thenComparing(Observation::mchNo, NULLABLE_STRING_ORDER)
            .thenComparing(Observation::businessType, NULLABLE_STRING_ORDER)
            .thenComparing(Observation::phase, NULLABLE_STRING_ORDER)
            .thenComparing(Observation::channel, NULLABLE_STRING_ORDER)
            .thenComparing(Observation::abnormalType, NULLABLE_STRING_ORDER)
            .thenComparing(Observation::errorCode, NULLABLE_STRING_ORDER)
            .thenComparing(Observation::normalizedDescription, NULLABLE_STRING_ORDER);

    private final SimilarityProvider similarityProvider;

    public CompleteLinkGrouper(SimilarityProvider similarityProvider) {
        this.similarityProvider = Objects.requireNonNull(similarityProvider, "similarityProvider");
    }

    @Override
    public GroupingResult group(Collection<Observation> observations, GroupingPolicy policy) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(policy, "policy");

        List<Observation> ordered = deduplicateAndSort(observations);
        Map<ObservationPair, Double> similarities = new HashMap<>();
        List<MutableGroup> groups = new ArrayList<>();

        for (Observation candidate : ordered) {
            MutableGroup bestGroup = null;
            double bestMinimumSimilarity = Double.NEGATIVE_INFINITY;

            for (MutableGroup group : groups) {
                Double resultingMinimum = group.minimumSimilarityWith(candidate, policy, similarities);
                if (resultingMinimum == null) {
                    continue;
                }
                if (bestGroup == null || resultingMinimum > bestMinimumSimilarity) {
                    bestGroup = group;
                    bestMinimumSimilarity = resultingMinimum;
                }
                // Equal scores deliberately retain the first group. Groups are created in stable
                // observation order, so this resolves ties reproducibly.
            }

            if (bestGroup == null) {
                groups.add(new MutableGroup(candidate));
            } else {
                bestGroup.add(candidate, bestMinimumSimilarity);
            }
        }

        List<EventGroup> resultGroups = groups.stream()
                .sorted(Comparator.comparing(MutableGroup::anchor, OBSERVATION_ORDER))
                .map(MutableGroup::toEventGroup)
                .toList();
        return new GroupingResult(resultGroups, ALGORITHM_VERSION);
    }

    private static List<Observation> deduplicateAndSort(Collection<Observation> observations) {
        Map<String, Observation> byId = new LinkedHashMap<>();
        for (Observation observation : observations) {
            Objects.requireNonNull(observation, "observations must not contain null");
            Observation previous = byId.putIfAbsent(observation.id(), observation);
            if (previous != null && !previous.equals(observation)) {
                throw new IllegalArgumentException("conflicting observations share id " + observation.id());
            }
        }
        return byId.values().stream().sorted(OBSERVATION_ORDER).toList();
    }

    private static boolean pairCanBeGrouped(Observation left, Observation right) {
        if (known(left.errorCode()) && known(right.errorCode()) && !left.errorCode().equals(right.errorCode())) {
            return false;
        }
        if (known(left.mchNo()) && known(right.mchNo()) && !left.mchNo().equals(right.mchNo())) return false;
        if (known(left.businessType()) && known(right.businessType()) && !left.businessType().equals(right.businessType())) return false;
        if (known(left.phase()) && known(right.phase()) && !left.phase().equals(right.phase())) return false;
        if (known(left.channel()) && known(right.channel()) && !left.channel().equals(right.channel())) return false;
        if (known(left.abnormalType()) && known(right.abnormalType()) && !left.abnormalType().equals(right.abnormalType())) return false;
        if (left.tradeNo().equals(right.tradeNo())) {
            return true;
        }
        return knownAndEqual(left.mchNo(), right.mchNo())
                && knownAndEqual(left.businessType(), right.businessType())
                && knownAndEqual(left.phase(), right.phase())
                && knownAndEqual(left.channel(), right.channel())
                && knownAndEqual(left.abnormalType(), right.abnormalType());
    }

    private static boolean knownAndEqual(String left, String right) {
        return known(left) && known(right) && left.equals(right);
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank();
    }

    private double checkedSimilarity(
            Observation left,
            Observation right,
            Map<ObservationPair, Double> cache) {
        ObservationPair key = ObservationPair.of(left, right);
        double score = cache.computeIfAbsent(key, ignored -> similarityProvider.similarity(left, right));
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("similarity provider must return a finite score");
        }
        return score;
    }

    private record ObservationPair(String firstId, String secondId) {
        private static ObservationPair of(Observation left, Observation right) {
            return left.id().compareTo(right.id()) <= 0
                    ? new ObservationPair(left.id(), right.id())
                    : new ObservationPair(right.id(), left.id());
        }
    }

    private final class MutableGroup {
        private final List<Observation> observations = new ArrayList<>();
        private double minimumSimilarity = Double.POSITIVE_INFINITY;
        private boolean hasSimilarity;

        private MutableGroup(Observation first) {
            observations.add(first);
        }

        private Observation anchor() {
            return observations.getFirst();
        }

        private Double minimumSimilarityWith(
            Observation candidate,
            GroupingPolicy policy,
            Map<ObservationPair, Double> similarityCache) {
            Observation first = observations.getFirst();
            if (Duration.between(first.occurredAt(), candidate.occurredAt()).compareTo(policy.timeWindow()) > 0) {
                return null;
            }

            double newMinimum = hasSimilarity ? minimumSimilarity : Double.POSITIVE_INFINITY;
            for (Observation member : observations) {
                if (!pairCanBeGrouped(member, candidate)) {
                    return null;
                }
                double score = checkedSimilarity(member, candidate, similarityCache);
                if (score < policy.threshold()) {
                    return null;
                }
                newMinimum = Math.min(newMinimum, score);
            }
            return newMinimum;
        }

        private void add(Observation observation, double resultingMinimum) {
            observations.add(observation);
            minimumSimilarity = resultingMinimum;
            hasSimilarity = true;
        }

        private EventGroup toEventGroup() {
            List<Observation> members = List.copyOf(observations);
            int distinctTrades = (int) members.stream().map(Observation::tradeNo).distinct().count();
            return new EventGroup(
                    members,
                    distinctTrades,
                    hasSimilarity ? java.util.OptionalDouble.of(minimumSimilarity) : java.util.OptionalDouble.empty(),
                    ALGORITHM_VERSION);
        }
    }
}
