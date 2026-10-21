package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.incidents.domain.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Advisory only. The existing audited merge endpoint remains the sole mutation path. */
@Service
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
public class PayLensMergeSuggestionService {
    private final JdbcTemplate jdbc;
    private final PayLensIncidentBatchService batches;
    private final PayLensIncidentSimilarityProvider similarity;
    private final boolean enabled;
    private final String calibrationId;
    private final double threshold;
    private final String merchant;
    private final Map<String, Double> scoreCache = new ConcurrentHashMap<>();

    public PayLensMergeSuggestionService(JdbcTemplate jdbc, PayLensIncidentBatchService batches,
                                          PayLensIncidentSimilarityProvider similarity,
                                          @Value("${paylens.incidents.suggestions-enabled:false}") boolean enabled,
                                          @Value("${paylens.incidents.suggestion-calibration-id:}") String calibrationId,
                                          @Value("${paylens.incidents.suggestion-threshold:0.82}") double threshold,
                                          @Value("${paylens.demo.mch-no:}") String merchant) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.similarity = similarity;
        this.enabled = enabled;
        this.calibrationId = calibrationId;
        this.threshold = threshold;
        this.merchant = merchant;
    }

    public Suggestions forIncident(String id) {
        var source = batches.detail(id); // merchant-scoped lookup before candidate access
        if (!"OPEN".equals(source.reviewStatus())) return new Suggestions("UNCALIBRATED", List.of());
        if (!enabled || calibrationId.isBlank()) return new Suggestions("UNCALIBRATED", List.of());
        if (!similarity.semanticReady()) return new Suggestions("UNAVAILABLE", List.of());
        List<String> ids = jdbc.query("""
                SELECT id FROM paylens_incident WHERE batch_id=? AND mch_no=? AND id<>? AND review_status='OPEN'
                 ORDER BY id LIMIT 500
                """, (rs, row) -> rs.getString(1), source.batchId(), merchant, id);
        List<PayLensIncidentBatchService.IncidentDetail> candidates = ids.stream().map(batches::detail)
                .filter(other -> compatible(source.members(), other.members())).toList();
        List<Observation> all = new ArrayList<>(source.members());
        candidates.forEach(candidate -> all.addAll(candidate.members()));
        try {
            similarity.prepare(all);
            List<Suggestion> ranked = candidates.stream().map(candidate -> {
                double score = score(source, candidate);
                Observation representative = candidate.members().getFirst();
                return new Suggestion(candidate.id(), source.version(), candidate.version(),
                        representative.normalizedDescription(), score,
                        "SAME_MERCHANT_BUSINESS_PHASE_CHANNEL_TYPE_AND_10_MINUTES");
            }).filter(item -> item.similarity() >= threshold)
                    .sorted(Comparator.comparingDouble(Suggestion::similarity).reversed()
                            .thenComparing(Suggestion::incidentId)).limit(3).toList();
            return new Suggestions("READY", ranked);
        } catch (RuntimeException exception) {
            return new Suggestions("UNAVAILABLE", List.of());
        } finally {
            similarity.clearPrepared();
        }
    }

    private double score(PayLensIncidentBatchService.IncidentDetail source,
                         PayLensIncidentBatchService.IncidentDetail candidate) {
        String model = String.valueOf(similarity.modelMetadata().get("modelFileSha256"));
        String key = merchant + ":" + source.id() + ":" + source.version() + ":" + candidate.id() + ":"
                + candidate.version() + ":" + model;
        if (scoreCache.size() > 10_000) scoreCache.clear();
        return scoreCache.computeIfAbsent(key, ignored -> {
            double minimum = 1;
            for (Observation left : source.members()) {
                for (Observation right : candidate.members()) {
                    minimum = Math.min(minimum, similarity.similarity(left, right));
                }
            }
            return minimum;
        });
    }

    static boolean compatible(List<Observation> left, List<Observation> right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        Instant first = left.stream().map(Observation::occurredAt).min(Instant::compareTo).orElseThrow();
        Instant last = right.stream().map(Observation::occurredAt).max(Instant::compareTo).orElseThrow();
        Instant otherFirst = right.stream().map(Observation::occurredAt).min(Instant::compareTo).orElseThrow();
        Instant otherLast = left.stream().map(Observation::occurredAt).max(Instant::compareTo).orElseThrow();
        if (Duration.between(first.isBefore(otherFirst) ? first : otherFirst,
                last.isAfter(otherLast) ? last : otherLast).compareTo(Duration.ofMinutes(10)) > 0) return false;
        for (Observation a : left) for (Observation b : right) {
            if (!sameKnown(a.mchNo(), b.mchNo()) || !sameKnown(a.businessType(), b.businessType()) ||
                    !sameKnown(a.phase(), b.phase()) || !sameKnown(a.channel(), b.channel()) ||
                    !sameKnown(a.abnormalType(), b.abnormalType())) return false;
            if (known(a.errorCode()) && known(b.errorCode()) && !a.errorCode().equals(b.errorCode())) return false;
        }
        return true;
    }

    private static boolean known(String value) { return value != null && !value.isBlank(); }
    private static boolean sameKnown(String a, String b) { return known(a) && a.equals(b); }

    public record Suggestions(String status, List<Suggestion> items) {}
    public record Suggestion(String incidentId, int sourceVersion, int candidateVersion,
                             String representativeDescription, double similarity, String ruleCheck) {}
}
