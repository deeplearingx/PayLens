package cn.daxpay.open.paylens.incidents.domain;

/** Supplies a deterministic, symmetric semantic similarity score for two observations. */
@FunctionalInterface
public interface SimilarityProvider {

    double similarity(Observation left, Observation right);
}
