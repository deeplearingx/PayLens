package cn.daxpay.open.paylens.incidents.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/** One deterministic candidate incident and the evidence supporting its grouping. */
public record EventGroup(
        List<Observation> observations,
        int distinctTradeCount,
        OptionalDouble minimumPairwiseSimilarity,
        String algorithmVersion) {

    public EventGroup {
        observations = List.copyOf(observations);
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("observations must not be empty");
        }
        Set<String> tradeNumbers = new HashSet<>();
        observations.forEach(observation -> tradeNumbers.add(observation.tradeNo()));
        if (distinctTradeCount != tradeNumbers.size()) {
            throw new IllegalArgumentException("distinctTradeCount must match the unique trade numbers");
        }
        Objects.requireNonNull(minimumPairwiseSimilarity, "minimumPairwiseSimilarity");
        if (observations.size() == 1 && minimumPairwiseSimilarity.isPresent()) {
            throw new IllegalArgumentException("a singleton group has no pairwise similarity");
        }
        if (observations.size() > 1 && minimumPairwiseSimilarity.isEmpty()) {
            throw new IllegalArgumentException("a multi-observation group must include pairwise similarity");
        }
        if (minimumPairwiseSimilarity.isPresent()
                && !Double.isFinite(minimumPairwiseSimilarity.getAsDouble())) {
            throw new IllegalArgumentException("minimumPairwiseSimilarity must be finite");
        }
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion must not be blank");
        }
    }
}
