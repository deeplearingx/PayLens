package cn.daxpay.open.paylens.analysis;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import cn.daxpay.open.paylens.incidents.domain.CompleteLinkGrouper;
import cn.daxpay.open.paylens.incidents.domain.EventGroup;
import cn.daxpay.open.paylens.incidents.domain.GroupingPolicy;
import cn.daxpay.open.paylens.incidents.domain.GroupingResult;
import cn.daxpay.open.paylens.incidents.domain.GroupingThresholdCalibrator;
import cn.daxpay.open.paylens.incidents.domain.Observation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline calibration. Labels are read only by the evaluator and never embedded or sent to a model. */
class IncidentGroupingEvaluationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void calibratesOnDevelopmentAndReportsFrozenThresholdOnHoldout() throws Exception {
        Path root = findRepositoryRoot();
        Path modelDirectory = findModelDirectory(root);
        Assumptions.assumeTrue(Files.isRegularFile(modelDirectory.resolve("model.onnx"))
                        && Files.isRegularFile(modelDirectory.resolve("reference-validation.json")),
                "Prepare the pinned local BGE ONNX model before grouping evaluation.");

        PayLensEmbeddingProperties properties = new PayLensEmbeddingProperties();
        properties.setModelDirectory(modelDirectory.toString());
        BgeSmallZhOnnxEmbeddingModel model = new BgeSmallZhOnnxEmbeddingModel(properties, new ThreadPoolTaskExecutor());
        try {
            model.initializeLocalFiles();
            List<EvaluationCase> development = readCases(root.resolve("docs/paylens/grouping-evaluation/development.json"));
            List<EvaluationCase> holdout = readCases(root.resolve("docs/paylens/grouping-evaluation/holdout.json"));
            assertEquals(100, development.size());
            assertEquals(100, holdout.size());

            Similarities developmentSimilarities = score(model, development);
            GroupingThresholdCalibrator.Result calibration = GroupingThresholdCalibrator.calibrate(
                    developmentSimilarities.labelledPairs());

            Similarities holdoutSimilarities = score(model, holdout);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("model", "BAAI/bge-small-zh-v1.5");
            report.put("modelRevision", model.modelRevision());
            report.put("modelSha256", model.modelSha256());
            report.put("dataType", "synthetic-curated");
            report.put("developmentObservations", development.size());
            report.put("holdoutObservations", holdout.size());
            report.put("hardBoundaryEvaluation", "Single compatible boundary in these fixtures; cross-boundary rejection is covered by domain tests.");
            report.put("semanticDecision", calibration.decision());
            report.put("ruleBaselineRecall", calibration.ruleRecall());
            report.put("developmentCurve", calibration.curve());
            report.put("selectedThreshold", calibration.selected().map(GroupingThresholdCalibrator.Metrics::threshold).orElse(null));
            report.put("developmentSelectedPairwiseMetrics", calibration.selected().orElse(null));
            if (calibration.selected().isPresent()) {
                double threshold = calibration.selected().orElseThrow().threshold();
                report.put("developmentSelectedClusterMetrics", clusterMetrics(development, developmentSimilarities, threshold));
                report.put("developmentRuleClusterMetrics", clusterMetrics(development, developmentSimilarities, null));
                report.put("holdoutPairwiseMetrics", pairMetrics(holdoutSimilarities, threshold));
                report.put("holdoutSemanticClusterMetrics", clusterMetrics(holdout, holdoutSimilarities, threshold));
                report.put("holdoutRuleClusterMetrics", clusterMetrics(holdout, holdoutSimilarities, null));
            } else {
                report.put("developmentSelectedClusterMetrics", null);
                report.put("developmentRuleClusterMetrics", clusterMetrics(development, developmentSimilarities, null));
                report.put("holdoutPairwiseMetrics", null);
                report.put("holdoutSemanticClusterMetrics", null);
                report.put("holdoutRuleClusterMetrics", clusterMetrics(holdout, holdoutSimilarities, null));
            }

            Path output = root.resolve("paylens-demo/target/incident-grouping-evaluation.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, JSON.writeValueAsString(report));
            System.out.println("Incident grouping evaluation: " + output.toAbsolutePath());
            assertTrue(calibration.curve().size() == 10);
        } finally {
            model.close();
        }
    }

    private Similarities score(BgeSmallZhOnnxEmbeddingModel model, List<EvaluationCase> cases) {
        List<float[]> vectors = new ArrayList<>(cases.size());
        for (int start = 0; start < cases.size(); start += model.maxBatchSize()) {
            List<String> texts = cases.subList(start, Math.min(cases.size(), start + model.maxBatchSize()))
                    .stream().map(EvaluationCase::description).toList();
            List<Embedding> batch = model.call(new EmbeddingRequest(texts, null)).getResults();
            batch.forEach(embedding -> vectors.add(embedding.getOutput()));
        }

        List<GroupingThresholdCalibrator.Pair> labelledPairs = new ArrayList<>();
        Map<IdPair, Double> scores = new HashMap<>();
        for (int left = 0; left < cases.size(); left++) {
            for (int right = left + 1; right < cases.size(); right++) {
                EvaluationCase a = cases.get(left);
                EvaluationCase b = cases.get(right);
                double similarity = cosine(vectors.get(left), vectors.get(right));
                scores.put(new IdPair(a.id(), b.id()), similarity);
                boolean sameIncident = a.eventLabel().equals(b.eventLabel());
                boolean ruleWouldGroup = normalize(a.description()).equals(normalize(b.description()));
                labelledPairs.add(new GroupingThresholdCalibrator.Pair(similarity, sameIncident, ruleWouldGroup));
            }
        }
        return new Similarities(List.copyOf(labelledPairs), Map.copyOf(scores));
    }

    private Map<String, Object> pairMetrics(Similarities similarities, double threshold) {
        long tp = 0, fp = 0, fn = 0, tn = 0;
        for (GroupingThresholdCalibrator.Pair pair : similarities.labelledPairs()) {
            boolean predicted = pair.similarity() >= threshold;
            if (predicted && pair.sameIncident()) tp++;
            else if (predicted) fp++;
            else if (pair.sameIncident()) fn++;
            else tn++;
        }
        return metrics(threshold, tp, fp, fn, tn);
    }

    private Map<String, Object> clusterMetrics(List<EvaluationCase> cases, Similarities similarities, Double threshold) {
        double activeThreshold = threshold == null ? GroupingPolicy.MIN_THRESHOLD : threshold;
        var grouper = new CompleteLinkGrouper((left, right) -> threshold == null
                ? (normalize(left.normalizedDescription()).equals(normalize(right.normalizedDescription())) ? 1.0 : 0.0)
                : similarities.score(left.id(), right.id()));
        GroupingResult result = grouper.group(observations(cases),
                new GroupingPolicy(activeThreshold, GroupingPolicy.MAX_TIME_WINDOW));
        Map<String, Integer> eventByObservation = new HashMap<>();
        int groupId = 0;
        for (EventGroup group : result.groups()) {
            int currentGroup = groupId++;
            group.observations().forEach(observation -> eventByObservation.put(observation.id(), currentGroup));
        }
        long tp = 0, fp = 0, fn = 0, tn = 0;
        for (int left = 0; left < cases.size(); left++) {
            for (int right = left + 1; right < cases.size(); right++) {
                EvaluationCase a = cases.get(left);
                EvaluationCase b = cases.get(right);
                boolean predicted = eventByObservation.get(a.id()).equals(eventByObservation.get(b.id()));
                boolean actual = a.eventLabel().equals(b.eventLabel());
                if (predicted && actual) tp++;
                else if (predicted) fp++;
                else if (actual) fn++;
                else tn++;
            }
        }
        return metrics(threshold == null ? "RULE_ONLY" : threshold, tp, fp, fn, tn,
                "eventCount", result.groups().size());
    }

    private Map<String, Object> metrics(Object threshold, long tp, long fp, long fn, long tn) {
        return metrics(threshold, tp, fp, fn, tn, "eventCount", -1);
    }

    private Map<String, Object> metrics(Object threshold, long tp, long fp, long fn, long tn,
                                        String extraKey, int extraValue) {
        long predicted = tp + fp;
        long actual = tp + fn;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("threshold", threshold);
        metrics.put("truePositive", tp);
        metrics.put("falsePositive", fp);
        metrics.put("falseNegative", fn);
        metrics.put("trueNegative", tn);
        metrics.put("precision", predicted == 0 ? 0.0 : (double) tp / predicted);
        metrics.put("recall", actual == 0 ? 0.0 : (double) tp / actual);
        if (extraValue >= 0) metrics.put(extraKey, extraValue);
        return Map.copyOf(metrics);
    }

    private List<Observation> observations(List<EvaluationCase> cases) {
        return cases.stream().map(item -> new Observation(item.id(), item.id(), item.merchant(), item.businessType(),
                item.phase(), item.channel(), item.abnormalType(), item.errorCode(), Instant.parse(item.occurredAt()),
                item.description())).toList();
    }

    private List<EvaluationCase> readCases(Path path) throws Exception {
        List<EvaluationCase> cases = JSON.readValue(Files.readString(path), new TypeReference<>() { });
        return cases.stream().sorted(Comparator.comparing(EvaluationCase::id)).toList();
    }

    private Path findRepositoryRoot() {
        for (Path candidate = Path.of("").toAbsolutePath().normalize(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docs/paylens/grouping-evaluation/development.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate repository root and grouping evaluation fixtures.");
    }

    private Path findModelDirectory(Path root) {
        String configured = System.getenv("PAYLENS_EMBEDDING_MODEL_DIR");
        Path candidate = Path.of(configured == null || configured.isBlank() ? "models/bge-small-zh-v1.5" : configured);
        if (!candidate.isAbsolute()) candidate = Path.of("").toAbsolutePath().resolve(candidate).normalize();
        if (Files.isRegularFile(candidate.resolve("model.onnx"))) return candidate;
        return root.resolve("models/bge-small-zh-v1.5");
    }

    private String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private double cosine(float[] left, float[] right) {
        double score = 0;
        for (int i = 0; i < left.length; i++) score += (double) left[i] * right[i];
        return score;
    }

    private record EvaluationCase(String id, String eventLabel, String merchant, String businessType, String phase,
                                  String channel, String abnormalType, String errorCode, String occurredAt,
                                  String description) { }

    private record Similarities(List<GroupingThresholdCalibrator.Pair> labelledPairs, Map<IdPair, Double> scores) {
        double score(String left, String right) {
            return scores.getOrDefault(new IdPair(left, right), scores.get(new IdPair(right, left)));
        }
    }

    private record IdPair(String left, String right) {
        IdPair {
            if (left.compareTo(right) > 0) {
                String swap = left;
                left = right;
                right = swap;
            }
        }
    }
}
