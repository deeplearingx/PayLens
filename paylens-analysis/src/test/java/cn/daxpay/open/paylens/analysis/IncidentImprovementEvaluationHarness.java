package cn.daxpay.open.paylens.analysis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit 80-initial/40-repair maximum evaluation; never run by Surefire. */
public final class IncidentImprovementEvaluationHarness {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int LIMIT = 120;
    private IncidentImprovementEvaluationHarness() { }

    public static void main(String[] args) throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("PAYLENS_IMPROVEMENT_EVAL_RUN")))
            throw new IllegalStateException("Explicit evaluation flag required");
        Path root = Path.of("docs/paylens/improvement-evaluation").toAbsolutePath();
        Dataset dataset = JSON.readValue(Files.readString(root.resolve("analysis-holdout-v2.json")), Dataset.class);
        if (dataset.cases().size() != 40 || dataset.cases().stream().map(Case::id).distinct().count() != 40)
            throw new IllegalArgumentException("Expected 40 unique frozen cases");
        @SuppressWarnings("unchecked") Map<String, Object> retrieval = JSON.readValue(
                Files.readString(root.resolve("retrieval-offline-report.json")), Map.class);
        if (!Boolean.TRUE.equals(retrieval.get("thresholdCalibrated")))
            throw new IllegalStateException("Hybrid retrieval is not calibrated");
        String key = required("PAYLENS_AI_API_KEY"), url = required("PAYLENS_AI_BASE_URL"), modelName = required("PAYLENS_AI_MODEL");
        PayLensAiProperties properties = new PayLensAiProperties();
        properties.setMode("openai"); properties.setApiKey(key); properties.setBaseUrl(url); properties.setModel(modelName);
        properties.setRequestTimeoutSeconds(60); properties.setConnectTimeoutSeconds(10);
        OpenAiCompatibleDiagnosticModel model = new OpenAiCompatibleDiagnosticModel(properties, new SimpleMeterRegistry());
        MarkdownKnowledgeRetriever keyword = new MarkdownKnowledgeRetriever();
        Map<String, KnowledgeChunk> chunks = new HashMap<>();
        keyword.chunks().forEach(chunk -> chunks.put(chunk.id(), chunk));
        String identity = sha(Files.readString(root.resolve("analysis-holdout-v2.json")) +
                Files.readString(root.resolve("retrieval-offline-report.json")) +
                IncidentAnalysisPromptBuilder.versionMaterial() + model.configurationId());
        Path run = Path.of(System.getenv().getOrDefault("PAYLENS_IMPROVEMENT_EVAL_RUN_DIR",
                "docs/paylens/improvement-evaluation/run-" + OffsetDateTime.now().toLocalDateTime().toString().replace(':', '-')))
                .toAbsolutePath();
        Files.createDirectories(run);
        Path ledgerPath = run.resolve("ledger.json");
        Map<String, Object> ledger;
        if (Files.exists(ledgerPath)) {
            ledger = read(ledgerPath);
            if (!identity.equals(ledger.get("identity"))) throw new IllegalStateException("Frozen configuration changed");
        } else {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (Case item : dataset.cases()) for (String condition : List.of("KEYWORD", "HYBRID")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.id() + "-" + condition + "-INITIAL");
                row.put("caseId", item.id()); row.put("condition", condition);
                row.put("kind", "INITIAL"); row.put("status", "NOT_STARTED");
                calls.add(row);
            }
            Collections.shuffle(calls, new SecureRandom());
            ledger = new LinkedHashMap<>();
            ledger.put("identity", identity); ledger.put("budget", LIMIT); ledger.put("createdAt", OffsetDateTime.now().toString());
            ledger.put("calls", calls); ledger.put("blindKey", blindKey(dataset.cases()));
            save(ledgerPath, ledger);
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> calls = (List<Map<String, Object>>) ledger.get("calls");
        Map<String, Case> byId = new HashMap<>();
        dataset.cases().forEach(item -> byId.put(item.id(), item));
        IncidentAnalysisJsonValidator validator = new IncidentAnalysisJsonValidator(JSON);
        int maxThisRun = Integer.parseInt(System.getenv().getOrDefault("PAYLENS_IMPROVEMENT_EVAL_MAX_CALLS", "120"));
        if (maxThisRun < 0 || maxThisRun > LIMIT) throw new IllegalArgumentException("Run call budget out of bounds");
        int issued = 0;
        while (issued < maxThisRun && consumed(calls) < LIMIT) {
            Map<String, Object> next = calls.stream().filter(row -> "NOT_STARTED".equals(row.get("status"))).findFirst().orElse(null);
            if (next == null) {
                if (calls.stream().anyMatch(row -> "INITIAL".equals(row.get("kind")) && "NOT_STARTED".equals(row.get("status")))) break;
                addRepairs(calls, run, 20);
                save(ledgerPath, ledger);
                next = calls.stream().filter(row -> "NOT_STARTED".equals(row.get("status"))).findFirst().orElse(null);
                if (next == null) break;
            }
            Case item = byId.get(next.get("caseId"));
            String condition = (String) next.get("condition");
            List<String> ids = retrieved(retrieval, item.id(), condition);
            List<KnowledgeChunk> knowledge = ids.stream().map(chunks::get).toList();
            String initialPrompt = IncidentAnalysisPromptBuilder.build(item.input(), knowledge, JSON);
            String prompt = initialPrompt;
            if ("FORMAT_REPAIR".equals(next.get("kind"))) {
                Map<String, Object> first = read(run.resolve("raw").resolve(item.id() + "-" + condition + "-INITIAL.json"));
                try {
                    prompt = IncidentFormatRepair.prompt(initialPrompt, String.valueOf(first.get("content")),
                            String.valueOf(first.get("failureCode")), JSON);
                } catch (DiagnosticValidationException oversized) {
                    next.put("status", "SKIPPED_CONTEXT_TOO_LARGE");
                    next.put("failureCode", oversized.failureCode());
                    save(ledgerPath, ledger);
                    continue;
                }
            }
            if (prompt.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                next.put("status", "SKIPPED_CONTEXT_TOO_LARGE"); save(ledgerPath, ledger); continue;
            }
            next.put("status", "STARTED"); next.put("startedAt", OffsetDateTime.now().toString());
            save(ledgerPath, ledger); // a started call consumes budget even after process interruption
            issued++;
            System.out.println("Issuing " + consumed(calls) + "/" + LIMIT + " " + next.get("id"));
            long start = System.nanoTime();
            DiagnosticModelResponse response = null;
            IncidentAnalysisResult valid = null;
            String failure = null;
            try {
                response = model.analyzeDetailed(prompt);
                valid = validator.validate(response.content(), item.input(), new HashSet<>(ids));
            } catch (DiagnosticValidationException exception) {
                failure = exception.failureCode();
            } catch (DiagnosticModelException exception) {
                failure = exception.failureCode();
            } catch (Exception exception) {
                failure = "UNCLASSIFIED_ERROR";
            }
            long millis = Math.max(0, (System.nanoTime() - start) / 1_000_000);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("callId", next.get("id")); raw.put("caseId", item.id()); raw.put("condition", condition);
            raw.put("kind", next.get("kind")); raw.put("input", item.input()); raw.put("knowledgeIds", ids);
            raw.put("content", response == null ? null : response.content()); raw.put("validatedResult", valid);
            raw.put("failureCode", failure); raw.put("elapsedMs", millis);
            raw.put("model", response == null ? null : response.modelId());
            raw.put("promptTokens", response == null ? null : response.promptTokens());
            raw.put("completionTokens", response == null ? null : response.completionTokens());
            save(run.resolve("raw").resolve(next.get("id") + ".json"), raw);
            next.put("status", valid == null ? "FAILED" : "SUCCEEDED");
            next.put("failureCode", failure); next.put("elapsedMs", millis);
            next.put("promptTokens", response == null ? null : response.promptTokens());
            next.put("completionTokens", response == null ? null : response.completionTokens());
            next.put("completedAt", OffsetDateTime.now().toString());
            save(ledgerPath, ledger);
            if (failure != null && Set.of("MODEL_HTTP_ERROR", "MODEL_RATE_LIMITED", "MODEL_TIMEOUT").contains(failure)) {
                System.out.println("Provider failure; stopped with call charged"); break;
            }
        }
        report(run, calls, dataset.cases(), retrieval);
        System.out.println("Evaluation artifacts: " + run + "; consumed " + consumed(calls) + "/" + LIMIT);
    }

    private static void addRepairs(List<Map<String, Object>> calls, Path run, int perCondition) throws Exception {
        if (calls.stream().anyMatch(row -> "FORMAT_REPAIR".equals(row.get("kind")))) return;
        for (String condition : List.of("KEYWORD", "HYBRID")) {
            int added = 0;
            for (Map<String, Object> first : List.copyOf(calls)) {
                if (!condition.equals(first.get("condition")) || !"INITIAL".equals(first.get("kind")) ||
                        !"FAILED".equals(first.get("status"))) continue;
                if (!Set.of("INVALID_JSON", "INVALID_STRUCTURE").contains(first.get("failureCode"))) continue;
                Map<String, Object> raw = read(run.resolve("raw").resolve(first.get("id") + ".json"));
                if (raw.get("content") == null) continue;
                Map<String, Object> repair = new LinkedHashMap<>();
                repair.put("id", first.get("caseId") + "-" + condition + "-FORMAT_REPAIR");
                repair.put("caseId", first.get("caseId")); repair.put("condition", condition);
                repair.put("kind", "FORMAT_REPAIR"); repair.put("status", "NOT_STARTED");
                calls.add(repair);
                if (++added >= perCondition) break;
            }
        }
        Collections.shuffle(calls.subList(80, calls.size()), new SecureRandom());
    }

    @SuppressWarnings("unchecked")
    private static List<String> retrieved(Map<String, Object> report, String id, String condition) {
        Map<String, Object> sets = (Map<String, Object>) report.get("sets");
        Map<String, Object> holdout = (Map<String, Object>) sets.get("holdout");
        Map<String, Object> variant = (Map<String, Object>) holdout.get(condition.toLowerCase());
        Map<String, List<String>> rankings = (Map<String, List<String>>) variant.get("rankings");
        return rankings.get(id);
    }

    private static Map<String, String> blindKey(List<Case> cases) {
        SecureRandom random = new SecureRandom();
        Map<String, String> key = new LinkedHashMap<>();
        for (Case item : cases) key.put(item.id(), random.nextBoolean() ? "KEYWORD=X" : "HYBRID=X");
        return key;
    }

    private static void report(Path run, List<Map<String, Object>> calls, List<Case> cases,
                               Map<String, Object> retrieval) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("consumedCalls", consumed(calls)); summary.put("maximumCalls", LIMIT);
        summary.put("initialSuccess", calls.stream().filter(r -> "INITIAL".equals(r.get("kind")) && "SUCCEEDED".equals(r.get("status"))).count());
        summary.put("initialTotal", 80);
        summary.put("repairTriggered", calls.stream().filter(r -> "FORMAT_REPAIR".equals(r.get("kind"))).count());
        summary.put("repairSuccess", calls.stream().filter(r -> "FORMAT_REPAIR".equals(r.get("kind")) && "SUCCEEDED".equals(r.get("status"))).count());
        summary.put("totalPromptTokens", calls.stream().mapToLong(r -> number(r.get("promptTokens"))).sum());
        summary.put("totalCompletionTokens", calls.stream().mapToLong(r -> number(r.get("completionTokens"))).sum());
        summary.put("failureCounts", calls.stream().filter(r -> "FAILED".equals(r.get("status")))
                .collect(java.util.stream.Collectors.groupingBy(r -> String.valueOf(r.get("failureCode")), java.util.stream.Collectors.counting())));
        summary.put("retrievalHoldout", ((Map<?, ?>)((Map<?, ?>)retrieval.get("sets")).get("holdout")));
        summary.put("aiScoring", "PENDING_AI_ASSISTED_REVIEW_NOT_INDEPENDENT_HUMAN_VALIDATION");
        summary.put("queueTimeMs", "NOT_APPLICABLE_STANDALONE_HARNESS");
        save(run.resolve("summary.json"), summary);
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static long consumed(List<Map<String, Object>> calls) {
        return calls.stream().filter(r -> !"NOT_STARTED".equals(r.get("status")) &&
                !"SKIPPED_CONTEXT_TOO_LARGE".equals(r.get("status"))).count();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
    private static String sha(String input) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String, Object> read(Path path) throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> value = JSON.readValue(Files.readString(path), Map.class);
        return value;
    }
    private static void save(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writeValueAsString(value), StandardCharsets.UTF_8);
        try { Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    public record Dataset(String datasetVersion, List<Case> cases) { }
    public record Case(String id, IncidentAnalysisInput input, String category,
                       List<String> candidateKnowledgeIds, List<String> scoringPoints) { }
}
