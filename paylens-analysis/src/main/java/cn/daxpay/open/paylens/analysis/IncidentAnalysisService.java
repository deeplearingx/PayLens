package cn.daxpay.open.paylens.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
public class IncidentAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisService.class);
    private static final int MAX_CONTEXT_BYTES = 16 * 1024;

    private final IncidentAnalysisRepository repository;
    private final DiagnosticModel model;
    private final KnowledgeRetriever knowledgeRetriever;
    private final IncidentAnalysisJsonValidator validator;
    private final ObjectMapper mapper;
    private final ThreadPoolTaskExecutor executor;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean claimFailureLogged = new AtomicBoolean();
    private volatile boolean queueReady;

    public IncidentAnalysisService(IncidentAnalysisRepository repository, DiagnosticModel model,
                                   KnowledgeRetriever knowledgeRetriever, IncidentAnalysisJsonValidator validator,
                                   ObjectMapper mapper,
                                   @Qualifier("payLensAnalysisExecutor") ThreadPoolTaskExecutor executor,
                                   JdbcTemplate jdbc) {
        this.repository = repository;
        this.model = model;
        this.knowledgeRetriever = knowledgeRetriever;
        this.validator = validator;
        this.mapper = mapper;
        this.executor = executor;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverQueuedTasks() {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name='paylens_incident_analysis'",
                Integer.class);
        if (exists == null || exists == 0) return;
        repository.markInterruptedRunningFailed();
        queueReady = true;
        dispatchQueued();
    }

    public IncidentAnalysisTask submit(String mchNo, String incidentId, int incidentVersion,
                                       IncidentAnalysisInput rawInput) {
        if (mchNo == null || mchNo.isBlank() || incidentId == null || incidentId.isBlank() || incidentVersion < 1) {
            throw new IllegalArgumentException("事件分析关联信息无效");
        }
        IncidentAnalysisInput input = sanitize(rawInput);
        String evidenceJson = canonicalJson(input);
        String snapshotHash = sha256(evidenceJson);
        List<KnowledgeChunk> knowledge = knowledgeRetriever.retrieve(retrievalInput(input), 3).stream()
                .sorted(Comparator.comparing(KnowledgeChunk::id)).toList();
        String knowledgeJson = writeJson(knowledge);
        String modelConfigurationId = sha256(model.configurationId());
        String promptVersion = sha256(IncidentAnalysisPromptBuilder.versionMaterial());
        String knowledgeVersion = knowledgeRetriever.version();
        String reuseKey = sha256(mchNo + "\n" + incidentId + "\n" + incidentVersion + "\n" + snapshotHash +
                "\n" + modelConfigurationId + "\n" + promptVersion + "\n" + IncidentAnalysisPromptBuilder.SCHEMA_VERSION + "\n" +
                knowledgeVersion + "\n" + sha256(knowledgeJson));
        IncidentAnalysisExecution execution = new IncidentAnalysisExecution(OffsetDateTime.now(), snapshotHash,
                model.modelId(), modelConfigurationId, promptVersion, IncidentAnalysisPromptBuilder.SCHEMA_VERSION,
                knowledgeVersion, knowledge, false);
        IncidentAnalysisTask task = repository.insertIfAbsent(mchNo, incidentId, incidentVersion, reuseKey,
                snapshotHash, evidenceJson, writeJson(execution), knowledgeJson);
        if ("QUEUED".equals(task.status()) && !task.reused()) signalQueue();
        return repository.findById(mchNo, task.id());
    }

    public IncidentAnalysisTask get(String mchNo, String id) { return repository.findById(mchNo, id); }

    public IncidentAnalysisPage history(String mchNo, String incidentId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new IllegalArgumentException("分页参数超出范围");
        return new IncidentAnalysisPage(page, pageSize, repository.historyCount(mchNo, incidentId),
                repository.history(mchNo, incidentId, page, pageSize));
    }

    public IncidentAnalysisTask retry(String mchNo, String id) {
        IncidentAnalysisTask task = get(mchNo, id);
        if (!"FAILED".equals(task.status())) throw new IllegalStateException("仅失败的事件分析任务可以重试");
        if (!sameExecutionVersion(task.execution())) throw new ReanalysisRequiredException();
        if (repository.retry(mchNo, id) != 1) throw new IllegalStateException("仅失败的事件分析任务可以重试");
        signalQueue();
        return get(mchNo, id);
    }

    @Scheduled(fixedDelayString = "${paylens.analysis.dispatch-delay-ms:500}")
    public void dispatchQueued() {
        if (!queueReady) return;
        var pool = executor.getThreadPoolExecutor();
        int available = pool.getQueue().remainingCapacity() + Math.max(0, pool.getMaximumPoolSize() - pool.getActiveCount());
        int dispatchCount = Math.min(repository.queuedCount(), available);
        for (int i = 0; i < dispatchCount; i++) signalQueue();
    }

    private void signalQueue() {
        try { executor.execute(this::processNext); }
        catch (TaskRejectedException ignored) { /* a running worker wakes the durable queue when it exits */ }
    }

    private void processNext() {
        String id;
        try {
            id = repository.claimNext().orElse(null);
            claimFailureLogged.set(false);
        } catch (RuntimeException exception) {
            if (claimFailureLogged.compareAndSet(false, true)) {
                log.error("PayLens 事件分析任务领取失败，排队任务将在后续调度中重试", exception);
            }
            return;
        }
        if (id == null) return;
        long started = System.nanoTime();
        try {
            IncidentAnalysisExecution execution = repository.execution(id).orElseThrow();
            if (!sameExecutionVersion(execution)) {
                repository.markFailed(id, "REANALYSIS_REQUIRED", elapsed(started));
                return;
            }
            IncidentAnalysisInput input = repository.snapshot(id).orElseThrow();
            List<KnowledgeChunk> knowledge = repository.retrievedKnowledge(id);
            String prompt = IncidentAnalysisPromptBuilder.build(input, knowledge, mapper);
            if (prompt.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
                throw new IllegalArgumentException("模型上下文超过 16 KB");
            }
            DiagnosticModelResponse response = model.analyzeDetailed(prompt);
            Set<String> knowledgeIds = new HashSet<>();
            knowledge.forEach(chunk -> knowledgeIds.add(chunk.id()));
            IncidentAnalysisResult result = validator.validate(response.content(), input, knowledgeIds);
            repository.markSucceeded(id, writeJson(result), elapsed(started), response);
        } catch (Exception exception) {
            repository.markFailed(id, failureCode(exception), elapsed(started));
        } finally {
            signalQueue();
        }
    }

    private IncidentAnalysisInput sanitize(IncidentAnalysisInput input) {
        List<DiagnosticEvidence> evidence = input.evidence().stream().filter(item -> item != null && item.id() != null && !item.id().isBlank())
                .map(item -> new DiagnosticEvidence(item.id(), item.type(), item.occurredAt(),
                        SensitiveTextSanitizer.sanitize(item.summary())))
                .sorted(Comparator.comparing(DiagnosticEvidence::occurredAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DiagnosticEvidence::id))
                .toList();
        IncidentAnalysisInput sanitized = new IncidentAnalysisInput(input.statistics(), evidence, input.omittedEvidenceCount());
        if (canonicalJson(sanitized).getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
            throw new IllegalArgumentException("事件证据超过 16 KB，请减少代表性证据");
        }
        return sanitized;
    }

    private DiagnosticInput retrievalInput(IncidentAnalysisInput input) {
        return new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT", input.evidence());
    }

    private String canonicalJson(IncidentAnalysisInput input) {
        Map<String, Object> sorted = new TreeMap<>();
        sorted.put("statistics", sortMap(input.statistics()));
        sorted.put("evidence", input.evidence().stream().sorted(Comparator.comparing(DiagnosticEvidence::occurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(DiagnosticEvidence::id)).toList());
        sorted.put("omittedEvidenceCount", input.omittedEvidenceCount());
        return writeJson(sorted);
    }

    private Object sortMap(Map<String, Object> source) {
        Map<String, Object> result = new TreeMap<>();
        source.forEach((key, value) -> result.put(key, value instanceof Map<?, ?> map ? sortMap((Map<String, Object>) map) : value));
        return result;
    }

    private boolean sameExecutionVersion(IncidentAnalysisExecution execution) {
        return execution != null && !execution.legacy() && sha256(model.configurationId()).equals(execution.modelConfigurationId()) &&
                sha256(IncidentAnalysisPromptBuilder.versionMaterial()).equals(execution.promptVersion()) &&
                IncidentAnalysisPromptBuilder.SCHEMA_VERSION.equals(execution.outputSchemaVersion()) &&
                knowledgeRetriever.version().equals(execution.knowledgeVersion());
    }

    private String failureCode(Exception exception) {
        if (exception instanceof DiagnosticValidationException validation) return validation.failureCode();
        if (exception instanceof DiagnosticModelException modelFailure) return modelFailure.failureCode();
        if (exception instanceof java.net.http.HttpTimeoutException) return "MODEL_TIMEOUT";
        if (exception instanceof IllegalArgumentException) return "INVALID_ANALYSIS_DATA";
        if (exception instanceof TaskRejectedException) return "QUEUE_FULL";
        return "INCIDENT_ANALYSIS_FAILED";
    }

    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("事件分析内容无法序列化", exception); }
    }
    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record IncidentAnalysisPage(int page, int pageSize, long total, List<IncidentAnalysisTask> items) {
        public IncidentAnalysisPage { items = List.copyOf(items); }
    }
}
