package cn.daxpay.open.paylens.analysis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Explicitly invoked, resumable event-analysis A/B evaluator. This is not a Surefire test. */
public final class IncidentModelEvaluationHarness {
    private static final int CALL_LIMIT = 38;
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String OUTPUT_RULES = "facts, differences, and hypotheses must cite known evidence; knowledge IDs must be from the prompt";

    private IncidentModelEvaluationHarness() { }

    public static void main(String[] args) throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("PAYLENS_INCIDENT_EVAL_RUN"))) {
            throw new IllegalStateException("Set PAYLENS_INCIDENT_EVAL_RUN=true through the evaluation script to authorize real model calls.");
        }
        String apiKey = required("PAYLENS_AI_API_KEY");
        String baseUrl = required("PAYLENS_AI_BASE_URL");
        String modelId = required("PAYLENS_AI_MODEL");
        Path casesPath = Path.of(required("PAYLENS_INCIDENT_EVAL_CASES")).toAbsolutePath().normalize();
        int requestedCalls = Integer.parseInt(System.getenv().getOrDefault("PAYLENS_INCIDENT_EVAL_MAX_CALLS", "38"));
        if (requestedCalls < 0 || requestedCalls > CALL_LIMIT) throw new IllegalArgumentException("The event evaluation call limit is 38.");
        String resumeValue = System.getenv("PAYLENS_INCIDENT_EVAL_RESUME");
        PayLensAiProperties properties = new PayLensAiProperties();
        properties.setMode("openai");
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setModel(modelId);
        properties.setConnectTimeoutSeconds(integerEnv("PAYLENS_AI_CONNECT_TIMEOUT_SECONDS", 10));
        properties.setRequestTimeoutSeconds(integerEnv("PAYLENS_AI_REQUEST_TIMEOUT_SECONDS", 60));

        Dataset dataset = MAPPER.readValue(Files.readString(casesPath, StandardCharsets.UTF_8), Dataset.class);
        if (dataset.cases() == null || dataset.cases().size() != 19) throw new IllegalArgumentException("Expected exactly 19 frozen incident cases.");
        if (dataset.cases().stream().map(EvaluationCase::id).distinct().count() != 19) throw new IllegalArgumentException("Incident evaluation case IDs must be unique.");
        MarkdownKnowledgeRetriever retriever = new MarkdownKnowledgeRetriever();
        IncidentAnalysisJsonValidator validator = new IncidentAnalysisJsonValidator(MAPPER);
        OpenAiCompatibleDiagnosticModel model = new OpenAiCompatibleDiagnosticModel(properties, new SimpleMeterRegistry());
        String providerHost = java.net.URI.create(baseUrl).getHost();
        if (providerHost == null) throw new IllegalArgumentException("PAYLENS_AI_BASE_URL must have a valid host.");
        String modelConfigHash = sha256(model.configurationId());
        String casesHash = sha256(Files.readString(casesPath, StandardCharsets.UTF_8));
        String promptHash = sha256(IncidentAnalysisPromptBuilder.versionMaterial() + "\n" + OUTPUT_RULES);
        String identity = String.join("|", dataset.datasetVersion(), casesHash, modelConfigHash, promptHash,
                IncidentAnalysisPromptBuilder.SCHEMA_VERSION, retriever.version());

        Path runDirectory;
        Map<String, Object> ledger;
        Map<String, Map<String, String>> blindKey;
        if (resumeValue != null && !resumeValue.isBlank()) {
            runDirectory = Path.of(resumeValue).toAbsolutePath().normalize();
            Map<String, Object> manifest = readMap(runDirectory.resolve("manifest.json"));
            if (!identity.equals(manifest.get("identity"))) throw new IllegalStateException("Frozen event evaluation identity changed; cannot resume this run.");
            ledger = readMap(runDirectory.resolve("ledger.json"));
            blindKey = readBlindKey(runDirectory.resolve("blind-key.json"));
        } else {
            runDirectory = Path.of(System.getenv().getOrDefault("PAYLENS_INCIDENT_EVAL_RUN_DIR",
                    "docs/paylens/evaluations/incident-eval-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))))
                    .toAbsolutePath().normalize();
            Files.createDirectories(runDirectory);
            blindKey = createBlindKey(dataset.cases());
            ledger = createLedger(dataset.cases());
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("createdAt", OffsetDateTime.now().toString());
            manifest.put("identity", identity);
            manifest.put("datasetVersion", dataset.datasetVersion());
            manifest.put("casesHash", casesHash);
            manifest.put("modelConfigHash", modelConfigHash);
            manifest.put("providerHost", providerHost);
            manifest.put("model", modelId);
            manifest.put("promptVersion", IncidentAnalysisPromptBuilder.PROMPT_VERSION);
            manifest.put("promptHash", promptHash);
            manifest.put("outputSchemaVersion", IncidentAnalysisPromptBuilder.SCHEMA_VERSION);
            manifest.put("knowledgeVersion", retriever.version());
            manifest.put("maximumCalls", CALL_LIMIT);
            manifest.put("design", "19 events x evidence-only / evidence-plus-retrieval; one attempt per condition");
            saveJson(runDirectory.resolve("manifest.json"), manifest);
            saveJson(runDirectory.resolve("blind-key.json"), blindKey);
            saveJson(runDirectory.resolve("ledger.json"), ledger);
        }

        List<Map<String, Object>> calls = maps(ledger.get("calls"));
        writeRetrievalPreview(runDirectory, dataset.cases(), retriever);
        long consumedBefore = calls.stream().filter(call -> !"NOT_STARTED".equals(call.get("status"))).count();
        if (consumedBefore > CALL_LIMIT) throw new IllegalStateException("Persistent ledger exceeds the hard 38-call limit.");
        int slots = Math.min(requestedCalls, CALL_LIMIT - Math.toIntExact(consumedBefore));
        int issued = 0;
        for (Map<String, Object> call : calls) {
            if (!"NOT_STARTED".equals(call.get("status")) || issued >= slots) continue;
            String caseId = string(call.get("caseId"));
            EvaluationCase evaluationCase = dataset.cases().stream().filter(item -> item.id().equals(caseId)).findFirst().orElseThrow();
            IncidentAnalysisInput input = evaluationCase.input();
            List<KnowledgeChunk> retrieved = retriever.retrieve(new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT", input.evidence()), 3)
                    .stream().sorted(Comparator.comparing(KnowledgeChunk::id)).toList();
            boolean withKnowledge = "evidence_plus_retrieval".equals(call.get("condition"));
            List<KnowledgeChunk> usedKnowledge = withKnowledge ? retrieved : List.of();
            String prompt = IncidentAnalysisPromptBuilder.build(input, usedKnowledge, MAPPER);
            if (prompt.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) throw new IllegalArgumentException(caseId + " exceeds the production 16KB prompt limit.");

            // Persist before issuing the request. A STARTED call is consumed even if this process is interrupted.
            call.put("status", "STARTED");
            call.put("startedAt", OffsetDateTime.now().toString());
            call.put("condition", withKnowledge ? "evidence_plus_retrieval" : "evidence_only");
            saveJson(runDirectory.resolve("ledger.json"), ledger);
            issued++;
            System.out.printf("[%d/%d] %s %s%n", consumedBefore + issued, CALL_LIMIT, caseId, call.get("condition"));

            long startedNanos = System.nanoTime();
            DiagnosticModelResponse response = null;
            String failureCode = null;
            IncidentAnalysisResult validatedResult = null;
            boolean responseReceived = false;
            Boolean evidenceReferenceValid = null;
            Boolean knowledgeCitationValid = null;
            List<String> validationErrors = new ArrayList<>();
            try {
                response = model.analyzeDetailed(prompt);
                responseReceived = true;
                validatedResult = validator.validate(response.content(), input,
                        usedKnowledge.stream().map(KnowledgeChunk::id).collect(Collectors.toSet()));
                evidenceReferenceValid = true;
                knowledgeCitationValid = true;
            } catch (DiagnosticValidationException exception) {
                failureCode = exception.failureCode();
                validationErrors.add(failureCode);
                if ("INVALID_EVIDENCE_REFERENCE".equals(failureCode)) evidenceReferenceValid = false;
                if ("INVALID_KNOWLEDGE_CITATION".equals(failureCode)) knowledgeCitationValid = false;
            } catch (DiagnosticModelException exception) {
                failureCode = exception.failureCode();
            } catch (Exception exception) {
                // Do not persist exception text: provider messages can contain request details.
                failureCode = "UNCLASSIFIED_MODEL_OR_VALIDATION_ERROR";
            }
            long elapsedMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
            List<String> retrievedIds = retrieved.stream().map(KnowledgeChunk::id).toList();
            long candidateHits = retrievedIds.stream().filter(evaluationCase.candidateKnowledgeIds()::contains).count();
            Double recallAt3 = evaluationCase.candidateKnowledgeIds().isEmpty() ? null :
                    (double) candidateHits / evaluationCase.candidateKnowledgeIds().size();
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("callId", call.get("callId"));
            raw.put("caseId", caseId);
            raw.put("condition", call.get("condition"));
            raw.put("input", input);
            raw.put("prompt", prompt);
            raw.put("retrieval", Map.of("candidateKnowledgeIds", evaluationCase.candidateKnowledgeIds(),
                    "retrievedKnowledgeIds", retrievedIds, "recallAt3", recallAt3 == null ? "NOT_APPLICABLE" : recallAt3));
            raw.put("knowledgeProvidedToModel", usedKnowledge);
            raw.put("responseReceived", responseReceived);
            raw.put("content", response == null ? null : response.content());
            raw.put("returnedModel", response == null ? null : response.modelId());
            raw.put("promptTokens", response == null ? null : response.promptTokens());
            raw.put("completionTokens", response == null ? null : response.completionTokens());
            raw.put("elapsedMs", elapsedMs);
            raw.put("valid", validatedResult != null);
            raw.put("evidenceReferenceValid", evidenceReferenceValid);
            raw.put("knowledgeCitationValid", knowledgeCitationValid);
            raw.put("failureCode", failureCode);
            raw.put("validationErrors", validationErrors);
            raw.put("validatedResult", validatedResult);
            saveJson(runDirectory.resolve("raw").resolve(string(call.get("callId")) + ".json"), raw);
            call.put("status", validatedResult == null ? "FAILED" : "SUCCEEDED");
            call.put("completedAt", OffsetDateTime.now().toString());
            call.put("failureCode", failureCode);
            call.put("elapsedMs", elapsedMs);
            call.put("promptTokens", response == null ? null : response.promptTokens());
            call.put("completionTokens", response == null ? null : response.completionTokens());
            saveJson(runDirectory.resolve("ledger.json"), ledger);
            if (failureCode != null && List.of("MODEL_HTTP_ERROR", "MODEL_RATE_LIMITED", "MODEL_TIMEOUT", "MODEL_INVALID_RESPONSE")
                    .contains(failureCode)) {
                System.out.println("Stopping after a provider compatibility failure. This call remains charged against the ledger.");
                break;
            }
        }
        revalidateSavedResponses(runDirectory, dataset.cases(), calls, retriever, validator);
        writeSummary(runDirectory, dataset.cases(), calls, blindKey, providerHost, modelId, retriever);
        System.out.println("Evaluation artifacts: " + runDirectory);
        Map<String, Object> summary = readMap(runDirectory.resolve("summary.json"));
        System.out.println("Consumed: " + ((Map<?, ?>) summary.get("calls")).get("consumed") + "/" + CALL_LIMIT +
                "; manual quality scoring remains pending.");
    }

    private static Map<String, Object> createLedger(List<EvaluationCase> cases) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (EvaluationCase item : cases) {
            calls.add(call(item.id() + "-A", item.id(), "evidence_only"));
            calls.add(call(item.id() + "-B", item.id(), "evidence_plus_retrieval"));
        }
        java.util.Collections.shuffle(calls, new SecureRandom());
        return new LinkedHashMap<>(Map.of("calls", calls));
    }

    private static void writeRetrievalPreview(Path runDirectory, List<EvaluationCase> cases,
                                              KnowledgeRetriever retriever) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EvaluationCase item : cases) {
            List<String> found = retriever.retrieve(new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT",
                    item.input().evidence()), 3).stream().map(KnowledgeChunk::id).toList();
            long hits = found.stream().filter(item.candidateKnowledgeIds()::contains).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", item.id());
            row.put("candidateKnowledgeIds", item.candidateKnowledgeIds());
            row.put("retrievedKnowledgeIds", found);
            row.put("recallAt3", item.candidateKnowledgeIds().isEmpty() ? "NOT_APPLICABLE" :
                    (double) hits / item.candidateKnowledgeIds().size());
            rows.add(row);
        }
        saveJson(runDirectory.resolve("retrieval-preview.json"), rows);
    }

    private static void revalidateSavedResponses(Path runDirectory, List<EvaluationCase> cases,
                                                 List<Map<String, Object>> calls, KnowledgeRetriever retriever,
                                                 IncidentAnalysisJsonValidator validator) throws IOException {
        for (Map<String, Object> call : calls) {
            if ("NOT_STARTED".equals(call.get("status"))) continue;
            Path rawPath = runDirectory.resolve("raw").resolve(string(call.get("callId")) + ".json");
            if (!Files.exists(rawPath)) continue;
            Map<String, Object> raw = readMap(rawPath);
            Object contentValue = raw.get("content");
            if (!(contentValue instanceof String content)) continue;
            String caseId = string(call.get("caseId"));
            EvaluationCase item = cases.stream().filter(testCase -> testCase.id().equals(caseId)).findFirst().orElseThrow();
            List<KnowledgeChunk> knowledge = "evidence_plus_retrieval".equals(call.get("condition"))
                    ? retriever.retrieve(new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT", item.input().evidence()), 3)
                    .stream().sorted(Comparator.comparing(KnowledgeChunk::id)).toList() : List.of();
            String failure = null;
            IncidentAnalysisResult result = null;
            Boolean evidenceValid = null;
            Boolean knowledgeValid = null;
            try {
                result = validator.validate(content, item.input(), knowledge.stream().map(KnowledgeChunk::id).collect(Collectors.toSet()));
                evidenceValid = true;
                knowledgeValid = true;
            } catch (DiagnosticValidationException exception) {
                failure = exception.failureCode();
                if ("INVALID_EVIDENCE_REFERENCE".equals(failure)) evidenceValid = false;
                if ("INVALID_KNOWLEDGE_CITATION".equals(failure)) knowledgeValid = false;
            }
            raw.put("valid", result != null);
            raw.put("failureCode", failure);
            raw.put("validationErrors", failure == null ? List.of() : List.of(failure));
            raw.put("evidenceReferenceValid", evidenceValid);
            raw.put("knowledgeCitationValid", knowledgeValid);
            raw.put("validatedResult", result);
            saveJson(rawPath, raw);
            call.put("failureCode", failure);
        }
        saveJson(runDirectory.resolve("ledger.json"), new LinkedHashMap<>(Map.of("calls", calls)));
    }

    private static Map<String, Object> call(String callId, String caseId, String condition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("callId", callId); result.put("caseId", caseId); result.put("condition", condition);
        result.put("status", "NOT_STARTED"); result.put("startedAt", null); result.put("completedAt", null);
        return result;
    }

    private static Map<String, Map<String, String>> createBlindKey(List<EvaluationCase> cases) {
        List<EvaluationCase> randomized = new ArrayList<>(cases);
        java.util.Collections.shuffle(randomized, new SecureRandom());
        Map<String, Map<String, String>> key = new LinkedHashMap<>();
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < randomized.size(); i++) {
            key.put(randomized.get(i).id(), Map.of("blindCaseId", String.format("B%02d", i + 1),
                    "evidenceOnlyVariant", random.nextBoolean() ? "X" : "Y"));
        }
        return key;
    }

    private static void writeSummary(Path runDirectory, List<EvaluationCase> cases, List<Map<String, Object>> calls,
                                     Map<String, Map<String, String>> blindKey, String providerHost, String modelId,
                                     KnowledgeRetriever retriever) throws IOException {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            Path rawPath = runDirectory.resolve("raw").resolve(string(call.get("callId")) + ".json");
            if (Files.exists(rawPath)) raw.add(readMap(rawPath));
        }
        List<Double> recall = cases.stream().filter(item -> !item.candidateKnowledgeIds().isEmpty())
                .map(item -> retrievalRecall(item, retriever)).toList();
        List<Long> latency = raw.stream().map(item -> item.get("elapsedMs")).filter(Number.class::isInstance)
                .map(Number.class::cast).map(Number::longValue).sorted().toList();
        long valid = raw.stream().filter(item -> Boolean.TRUE.equals(item.get("valid"))).count();
        long responded = raw.stream().filter(item -> Boolean.TRUE.equals(item.get("responseReceived"))).count();
        long promptTokens = raw.stream().map(item -> item.get("promptTokens")).filter(Number.class::isInstance).map(Number.class::cast).count();
        long completionTokens = raw.stream().map(item -> item.get("completionTokens")).filter(Number.class::isInstance).map(Number.class::cast).count();
        long consumed = calls.stream().filter(call -> !"NOT_STARTED".equals(call.get("status"))).count();
        long succeeded = calls.stream().filter(call -> "SUCCEEDED".equals(call.get("status"))).count();
        long failed = calls.stream().filter(call -> "FAILED".equals(call.get("status"))).count();
        long interrupted = calls.stream().filter(call -> "STARTED".equals(call.get("status"))).count();
        long pending = calls.stream().filter(call -> "NOT_STARTED".equals(call.get("status"))).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", OffsetDateTime.now().toString());
        summary.put("datasetVersion", "paylens-incident-eval-v1");
        summary.put("caseCount", cases.size()); summary.put("model", modelId); summary.put("providerHost", providerHost);
        summary.put("knowledgeVersion", retriever.version());
        summary.put("calls", Map.of("maximum", CALL_LIMIT, "consumed", consumed, "succeeded", succeeded,
                "failed", failed, "interrupted", interrupted, "notStarted", pending));
        summary.put("structureValidity", Map.of("valid", valid, "responsesReceived", responded));
        summary.put("evidenceReferenceValidity", Map.of("valid", countValid(raw, "evidenceReferenceValid"),
                "invalid", countInvalid(raw, "evidenceReferenceValid"), "notEvaluated", countUnknown(raw, "evidenceReferenceValid")));
        summary.put("knowledgeCitationValidity", Map.of("valid", countValid(raw, "knowledgeCitationValid"),
                "invalid", countInvalid(raw, "knowledgeCitationValid"), "notEvaluated", countUnknown(raw, "knowledgeCitationValid")));
        summary.put("retrievalRecallAt3", Map.of("measuredCases", recall.size(),
                "mean", recall.isEmpty() ? 0.0 : recall.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        summary.put("latencyMs", Map.of("measured", latency.size(), "median", percentile(latency, 0.50), "p95", percentile(latency, 0.95)));
        summary.put("tokenUsage", Map.of("responsesWithPromptTokens", promptTokens, "responsesWithCompletionTokens", completionTokens,
                "promptTokensUnavailable", raw.size() - promptTokens, "completionTokensUnavailable", raw.size() - completionTokens));
        summary.put("manualScoring", "PENDING_BLIND_HUMAN_REVIEW");
        saveJson(runDirectory.resolve("summary.json"), summary);
        writeScoringSheets(runDirectory, cases, raw, blindKey);
    }

    private static long countValid(List<Map<String, Object>> raw, String field) {
        return raw.stream().filter(item -> Boolean.TRUE.equals(item.get(field))).count();
    }

    private static long countInvalid(List<Map<String, Object>> raw, String field) {
        return raw.stream().filter(item -> Boolean.FALSE.equals(item.get(field))).count();
    }

    private static long countUnknown(List<Map<String, Object>> raw, String field) {
        return raw.stream().filter(item -> item.get(field) == null).count();
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        return values.get((int) Math.ceil(p * values.size()) - 1);
    }

    private static void writeScoringSheets(Path runDirectory, List<EvaluationCase> cases,
                                           List<Map<String, Object>> raw, Map<String, Map<String, String>> blindKey) throws IOException {
        List<String> scores = new ArrayList<>();
        scores.add("BlindCaseId,Variant,InputJson,OutputJson,ManualScore0to2,CitationSupport,ReviewerNotes");
        for (Map<String, Object> item : raw) {
            String id = string(item.get("caseId"));
            boolean evidenceOnly = "evidence_only".equals(item.get("condition"));
            Map<String, String> assignment = blindKey.get(id);
            String variant = assignment.get("evidenceOnlyVariant").equals(evidenceOnly ? "X" : "Y") ? "X" : "Y";
            String output = Objects.toString(item.get("content"), "CALL_FAILED: " + item.get("failureCode"));
            String inputJson = writeJson(item.get("input"));
            scores.add(csv(assignment.get("blindCaseId")) + "," + variant + "," + csv(inputJson) + "," + csv(output) + ",,,");
        }
        Files.writeString(runDirectory.resolve("blind-score-sheet.csv"), String.join("\n", scores), StandardCharsets.UTF_8);
        List<String> rubric = new ArrayList<>(); rubric.add("BlindCaseId,ScoringPoints,ManualScore0to2,ReviewerNotes");
        for (EvaluationCase item : cases) rubric.add(csv(blindKey.get(item.id()).get("blindCaseId")) + "," + csv(String.join("; ", item.scoringPoints())) + ",,");
        Files.writeString(runDirectory.resolve("blind-rubric.csv"), String.join("\n", rubric), StandardCharsets.UTF_8);
        saveJson(runDirectory.resolve("blind-key.json"), blindKey);
    }

    private static double retrievalRecall(EvaluationCase item, KnowledgeRetriever retriever) {
        List<String> found = retriever.retrieve(new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT",
                item.input().evidence()), 3).stream().map(KnowledgeChunk::id).toList();
        return (double) found.stream().filter(item.candidateKnowledgeIds()::contains).count() / item.candidateKnowledgeIds().size();
    }
    private static String csv(String value) {
        return "\"" + Objects.toString(value, "").replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
    private static String writeJson(Object value) { try { return MAPPER.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static String required(String key) { String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required."); return value; }
    private static int integerEnv(String key, int fallback) { try { return Integer.parseInt(System.getenv().getOrDefault(key, Integer.toString(fallback))); } catch (NumberFormatException ignored) { return fallback; } }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static void saveJson(Path path, Object value) throws IOException { Files.createDirectories(path.getParent()); Path temp = path.resolveSibling(path.getFileName() + ".tmp"); Files.writeString(temp, MAPPER.writeValueAsString(value), StandardCharsets.UTF_8); try { Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); } catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } }
    private static Map<String, Object> readMap(Path path) throws IOException { return MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
    @SuppressWarnings("unchecked") private static Map<String, Map<String, String>> readBlindKey(Path path) throws IOException { return (Map<String, Map<String, String>>) (Map<?, ?>) MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Map.class)); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
    @SuppressWarnings("unchecked") private static Map<String, Object> readMapObject(Object value) { return (Map<String, Object>) value; }

    public record Dataset(String datasetVersion, String purpose, List<EvaluationCase> cases) { }
    public record EvaluationCase(String id, IncidentAnalysisInput input, List<String> candidateKnowledgeIds,
                                 List<String> scoringPoints) {
        public EvaluationCase {
            candidateKnowledgeIds = candidateKnowledgeIds == null ? List.of() : List.copyOf(candidateKnowledgeIds);
            scoringPoints = scoringPoints == null ? List.of() : List.copyOf(scoringPoints);
        }
    }
}
