package cn.daxpay.open.paylens.analysis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DiagnosticAnalysisServiceTest {
    private static final String VALID_RESULT = """
            {"summary":"有证据支持的摘要","facts":[{"statement":"平台订单处于处理中","evidenceIds":["E-1"]}],
             "possibleCauses":[{"hypothesis":"成功回调可能未到达","evidenceIds":["E-1"],"verification":"查询通道通知记录"}],
             "checkSteps":["核对通道最终状态"],"missingInformation":[],"knowledgeCitations":[]}
            """;

    @Test
    void scheduledDispatcherWaitsForApplicationReadySchemaRecovery() {
        ObjectMapper mapper = JsonMapper.builder().build();
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        ThreadPoolTaskExecutor executor = executor(5);
        DiagnosticAnalysisService service = new DiagnosticAnalysisService(repository,
                new MutableKnowledgeRetriever("kb-1"), model("model-test", new AtomicInteger(), prompt -> VALID_RESULT),
                new DiagnosticJsonValidator(mapper), mapper, executor, new JdbcTemplate());
        try {
            service.dispatchQueued();
            verify(repository, never()).queuedSingleCount();

            service.recoverQueuedTasks();

            InOrder startup = inOrder(repository);
            startup.verify(repository).initialize();
            startup.verify(repository).markInterruptedRunningFailed();
            startup.verify(repository).queuedSingleCount();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void actualMockModelOutputPassesValidatorWithEvidenceAndNoEvidence() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        KnowledgeRetriever retriever = new KnowledgeRetriever() {
            private final List<KnowledgeChunk> chunks = List.of(new KnowledgeChunk("KB-TIMEOUT", "查单超时",
                    List.of("超时"), List.of("ANY"), "超时不能证明支付失败。"));
            @Override public List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit) {
                return input.evidence().isEmpty() ? List.of() : chunks;
            }
            @Override public String version() { return "kb-test"; }
        };
        ThreadPoolTaskExecutor executor = executor(8);
        try {
            DiagnosticAnalysisService service = service(repository, retriever, new MockDiagnosticModel(mapper), mapper, executor);
            AnalysisTask withEvidence = service.submit("M-1", input("T-MOCK-1", "回调成功"));
            AnalysisTask withoutEvidence = service.submit("M-1", new DiagnosticInput("T-MOCK-2", "UNKNOWN",
                    "UNKNOWN", "NONE", List.of()));
            awaitStatus(repository, withEvidence.id(), "SUCCEEDED");
            awaitStatus(repository, withoutEvidence.id(), "SUCCEEDED");

            DiagnosticResult evidenced = service.get("M-1", withEvidence.id()).result();
            assertEquals(List.of("E-1"), evidenced.facts().getFirst().evidenceIds());
            assertEquals("NOT_USED", evidenced.knowledgeUseStatus());
            assertTrue(evidenced.knowledgeOmissionReason().contains("假模型"));

            DiagnosticResult empty = service.get("M-1", withoutEvidence.id()).result();
            assertTrue(empty.facts().isEmpty());
            assertTrue(empty.possibleCauses().isEmpty());
            assertTrue(empty.checkSteps().isEmpty());
            assertEquals("NO_MATCH", empty.knowledgeUseStatus());
            assertTrue(!empty.missingInformation().isEmpty());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void retryRequiresTheSameExecutionVersions() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        MutableKnowledgeRetriever retriever = new MutableKnowledgeRetriever("kb-1");
        AtomicInteger modelCalls = new AtomicInteger();
        DiagnosticModel model = model("model-test", modelCalls, prompt ->
                modelCalls.get() == 1 ? "invalid json" : VALID_RESULT);
        ThreadPoolTaskExecutor executor = executor(8);
        try {
            DiagnosticAnalysisService service = service(repository, retriever, model, mapper, executor);
            AnalysisTask failed = service.submit("M-1", input("T-VERSION", "现场一"));
            awaitStatus(repository, failed.id(), "FAILED");
            retriever.currentVersion = "kb-2";
            assertThrows(ReanalysisRequiredException.class, () -> service.retry("M-1", failed.id()));
            AnalysisTask fresh = service.submit("M-1", input("T-VERSION", "现场一"));
            assertNotEquals(failed.id(), fresh.id());
            awaitStatus(repository, fresh.id(), "SUCCEEDED");
            assertEquals(2, modelCalls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void oneHundredConcurrentIdenticalSubmissionsShareOneModelCall() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        DiagnosticModel model = model("model-test", modelCalls, prompt -> {
            modelStarted.countDown();
            assertTrue(releaseModel.await(5, TimeUnit.SECONDS));
            return VALID_RESULT;
        });
        ThreadPoolTaskExecutor executor = executor(50);
        try {
            DiagnosticAnalysisService service = service(repository, model, mapper, executor, "kb-1");
            DiagnosticInput input = input("T-100", "同一现场");
            try (var callers = Executors.newFixedThreadPool(16)) {
                var futures = java.util.stream.IntStream.range(0, 100)
                        .mapToObj(index -> callers.submit(() -> service.submit("M-1", input)))
                        .toList();
                assertTrue(modelStarted.await(3, TimeUnit.SECONDS));
                List<AnalysisTask> submitted = futures.stream().map(future -> {
                    try { return future.get(3, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new AssertionError("并发提交未能及时返回", e); }
                }).toList();
                releaseModel.countDown();
                String taskId = submitted.getFirst().id();
                assertTrue(submitted.stream().allMatch(task -> taskId.equals(task.id())));
                assertEquals(1, repository.size());
                awaitStatus(repository, taskId, "SUCCEEDED");
                assertEquals(1, modelCalls.get());
                assertTrue(submitted.stream().anyMatch(AnalysisTask::reused));
            }
        } finally {
            releaseModel.countDown();
            executor.shutdown();
        }
    }

    @Test
    void evidenceMerchantAndKnowledgeVersionChangesDoNotReuseOldAnalysis() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        AtomicInteger modelCalls = new AtomicInteger();
        DiagnosticModel model = model("model-test", modelCalls, prompt -> VALID_RESULT);
        ThreadPoolTaskExecutor executor = executor(10);
        try {
            MutableKnowledgeRetriever retriever = new MutableKnowledgeRetriever();
            DiagnosticAnalysisService service = service(repository, retriever, model, mapper, executor);

            AnalysisTask first = service.submit("M-1", input("T-200", "通道超时"));
            AnalysisTask same = service.submit("M-1", input("T-200", "通道超时"));
            AnalysisTask changedEvidence = service.submit("M-1", input("T-200", "通道拒绝"));
            AnalysisTask otherMerchant = service.submit("M-2", input("T-200", "通道超时"));
            awaitStatus(repository, "M-1", first.id(), "SUCCEEDED");
            awaitStatus(repository, "M-1", changedEvidence.id(), "SUCCEEDED");
            awaitStatus(repository, "M-2", otherMerchant.id(), "SUCCEEDED");

            retriever.currentVersion = "kb-2";
            AnalysisTask changedKnowledge = service.submit("M-1", input("T-200", "通道超时"));

            assertEquals(first.id(), same.id());
            assertTrue(same.reused());
            assertNotEquals(first.id(), changedEvidence.id());
            assertNotEquals(first.id(), otherMerchant.id());
            assertNotEquals(first.id(), changedKnowledge.id());
            assertEquals(4, repository.size());
            awaitStatus(repository, "M-1", changedKnowledge.id(), "SUCCEEDED");
            assertEquals(4, modelCalls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void modelFailureIsVisibleAndExplicitRetryCanSucceed() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        AtomicInteger modelCalls = new AtomicInteger();
        DiagnosticModel model = model("model-test", modelCalls, prompt -> {
            if (modelCalls.get() == 1) throw new DiagnosticModelException("MODEL_RATE_LIMITED");
            return VALID_RESULT;
        });
        ThreadPoolTaskExecutor executor = executor(5);
        try {
            DiagnosticAnalysisService service = service(repository, model, mapper, executor, "kb-1");
            AnalysisTask submitted = service.submit("M-1", input("T-300", "查单失败"));

            awaitStatus(repository, submitted.id(), "FAILED");
            assertEquals("MODEL_RATE_LIMITED", repository.findById("M-1", submitted.id()).orElseThrow().failureReason());
            service.retry("M-1", submitted.id());
            awaitStatus(repository, submitted.id(), "SUCCEEDED");
            assertEquals(2, modelCalls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void reportsMissingKnowledgeCitationAsSpecificFailure() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        AtomicInteger modelCalls = new AtomicInteger();
        KnowledgeRetriever retriever = new KnowledgeRetriever() {
            @Override public List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit) {
                return List.of(new KnowledgeChunk("K-1", "关单后成功", List.of(), List.of("close", "success"),
                        "核查关单请求、通道成功时间和回调时间。"));
            }
            @Override public String version() { return "kb-test"; }
        };
        ThreadPoolTaskExecutor executor = executor(5);
        try {
            DiagnosticAnalysisService service = service(repository, retriever,
                    model("model-test", modelCalls, prompt -> VALID_RESULT), mapper, executor);
            AnalysisTask submitted = service.submit("M-1", input("T-CITATION", "关单后成功回调"));

            awaitStatus(repository, submitted.id(), "FAILED");

            assertEquals("MISSING_KNOWLEDGE_DECISION",
                    repository.findById("M-1", submitted.id()).orElseThrow().failureReason());
            assertEquals(1, modelCalls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void oversizedInputIsRejectedBeforeItCanBeQueued() {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        AtomicInteger modelCalls = new AtomicInteger();
        ThreadPoolTaskExecutor executor = executor(5);
        try {
            DiagnosticAnalysisService service = service(repository,
                    model("model-test", modelCalls, prompt -> VALID_RESULT), mapper, executor, "kb-1");
            List<DiagnosticEvidence> evidence = java.util.stream.IntStream.range(0, 9)
                    .mapToObj(index -> new DiagnosticEvidence("E-" + index, "SIMULATED_EVENT",
                            OffsetDateTime.parse("2026-09-24T10:00:00Z"), "x".repeat(2000)))
                    .toList();
            DiagnosticInput large = new DiagnosticInput("T-LARGE", "PROCESSING", "SUCCESS",
                    "CALLBACK_MISSING", evidence);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> service.submit("M-1", large));

            assertEquals(0, repository.size());
            assertEquals(0, modelCalls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void executorRejectionLeavesTaskDurableAndWorkerCompletionResumesQueue() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        InMemoryRepository repository = new InMemoryRepository(mapper);
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        DiagnosticModel model = model("model-test", modelCalls, prompt -> {
            if (modelCalls.get() == 1) {
                modelStarted.countDown();
                assertTrue(releaseModel.await(5, TimeUnit.SECONDS));
            }
            return VALID_RESULT;
        });
        ThreadPoolTaskExecutor executor = threadPoolExecutor(1, 0);
        try {
            DiagnosticAnalysisService service = service(repository, model, mapper, executor, "kb-1");
            DiagnosticInput blocker = input("T-BLOCKER", "先占满工作线程");
            DiagnosticInput rejected = input("T-REJECTED", "首次提交会被拒绝");
            AnalysisTask first = service.submit("M-1", blocker);
            assertTrue(modelStarted.await(3, TimeUnit.SECONDS));

            AnalysisTask queued = service.submit("M-1", rejected);
            assertEquals("QUEUED", queued.status());

            releaseModel.countDown();
            awaitStatus(repository, first.id(), "SUCCEEDED");
            service.dispatchQueued();
            awaitStatus(repository, queued.id(), "SUCCEEDED");
            awaitExecutorIdle(executor);
            assertEquals(2, modelCalls.get());
        } finally {
            releaseModel.countDown();
            executor.shutdown();
        }
    }

    private DiagnosticAnalysisService service(InMemoryRepository repository, DiagnosticModel model,
                                              ObjectMapper mapper, ThreadPoolTaskExecutor executor,
                                              String knowledgeVersion) {
        return service(repository, new MutableKnowledgeRetriever(knowledgeVersion), model, mapper, executor);
    }

    private DiagnosticAnalysisService service(InMemoryRepository repository, KnowledgeRetriever retriever,
                                              DiagnosticModel model, ObjectMapper mapper,
                                              ThreadPoolTaskExecutor executor) {
        DiagnosticAnalysisService service = new DiagnosticAnalysisService(repository, retriever, model,
                new DiagnosticJsonValidator(mapper), mapper, executor, new JdbcTemplate());
        service.recoverQueuedTasks();
        return service;
    }

    private DiagnosticModel model(String id, AtomicInteger calls, ThrowingFunction function) {
        return new DiagnosticModel() {
            @Override public String analyze(String prompt) throws Exception {
                calls.incrementAndGet();
                return function.apply(prompt);
            }
            @Override public String modelId() { return id; }
        };
    }

    private ThreadPoolTaskExecutor executor(int queueCapacity) {
        return threadPoolExecutor(2, queueCapacity);
    }

    private ThreadPoolTaskExecutor threadPoolExecutor(int workers, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("paylens-test-");
        executor.initialize();
        return executor;
    }

    private DiagnosticInput input(String tradeNo, String summary) {
        return new DiagnosticInput(tradeNo, "PROCESSING", "SUCCESS", "CALLBACK_MISSING",
                List.of(new DiagnosticEvidence("E-1", "SIMULATED_EVENT",
                        OffsetDateTime.parse("2026-09-24T10:00:00Z"), summary)));
    }

    private void awaitStatus(InMemoryRepository repository, String id, String expected) throws InterruptedException {
        awaitStatus(repository, "M-1", id, expected);
    }

    private void awaitStatus(InMemoryRepository repository, String mchNo, String id, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (repository.findById(mchNo, id).map(task -> expected.equals(task.status())).orElse(false)) return;
            Thread.sleep(10);
        }
        assertEquals(expected, repository.findById(mchNo, id).map(AnalysisTask::status).orElse("MISSING"));
    }

    private void awaitExecutorIdle(ThreadPoolTaskExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && executor.getThreadPoolExecutor().getActiveCount() != 0) {
            Thread.sleep(10);
        }
        assertEquals(0, executor.getThreadPoolExecutor().getActiveCount());
    }

    @FunctionalInterface
    private interface ThrowingFunction { String apply(String prompt) throws Exception; }

    private static final class MutableKnowledgeRetriever implements KnowledgeRetriever {
        private volatile String currentVersion;
        private MutableKnowledgeRetriever() { this("kb-1"); }
        private MutableKnowledgeRetriever(String version) { this.currentVersion = version; }
        @Override public List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit) { return List.of(); }
        @Override public String version() { return currentVersion; }
    }

    private static final class InMemoryRepository extends AnalysisTaskRepository {
        private final ObjectMapper mapper;
        private final Map<String, StoredTask> byReuseKey = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, StoredTask> byId = new java.util.concurrent.ConcurrentHashMap<>();

        private InMemoryRepository(ObjectMapper mapper) {
            super(new JdbcTemplate(), mapper);
            this.mapper = mapper;
        }

        @Override public void initialize() {}
        @Override public int markInterruptedRunningFailed() { return 0; }

        @Override public Optional<AnalysisTask> findByReuseKey(String mchNo, String reuseKey) {
            StoredTask task = byReuseKey.get(reuseKey);
            return task == null || !task.mchNo.equals(mchNo) ? Optional.empty() : Optional.of(task.toTask());
        }

        @Override public synchronized AnalysisTask insertIfAbsent(String mchNo, String tradeNo,
                                                                  String reuseKey, String snapshot, String snapshotHash,
                                                                  String modelId, String modelConfigurationId,
                                                                  String promptVersion, String outputSchemaVersion,
                                                                  String knowledgeVersion) {
            StoredTask prior = byReuseKey.get(reuseKey);
            if (prior != null) return prior.toTask().withReused(true);
            try {
                StoredTask task = new StoredTask(UUID.randomUUID().toString(), mchNo, tradeNo, reuseKey,
                        mapper.readValue(snapshot, DiagnosticInput.class), snapshotHash, modelId,
                        modelConfigurationId, promptVersion, outputSchemaVersion, knowledgeVersion);
                byReuseKey.put(reuseKey, task);
                byId.put(task.id, task);
                return task.toTask();
            } catch (JacksonException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public Optional<AnalysisTask> findById(String mchNo, String id) {
            StoredTask task = byId.get(id);
            return task == null || !task.mchNo.equals(mchNo) ? Optional.empty() : Optional.of(task.toTask());
        }

        private Optional<AnalysisTask> findByTradeNo(String mchNo, String tradeNo) {
            return byId.values().stream().filter(task -> task.mchNo.equals(mchNo) && task.tradeNo.equals(tradeNo))
                    .findFirst().map(StoredTask::toTask);
        }

        @Override public int transitionToRunning(String id) {
            StoredTask task = byId.get(id);
            if (task == null || !"QUEUED".equals(task.status)) return 0;
            task.status = "RUNNING";
            return 1;
        }

        @Override public synchronized Optional<String> claimNext() {
            StoredTask task = byId.values().stream().filter(item -> "QUEUED".equals(item.status)).findFirst().orElse(null);
            if (task == null) return Optional.empty();
            task.status = "RUNNING";
            return Optional.of(task.id);
        }

        @Override public int queuedCount() {
            return (int) byId.values().stream().filter(item -> "QUEUED".equals(item.status)).count();
        }
        @Override public int queuedSingleCount() { return queuedCount(); }

        @Override public void storeRetrievedKnowledge(String id, String knowledgeJson) {}

        @Override public Optional<DiagnosticInput> snapshot(String id) {
            return Optional.ofNullable(byId.get(id)).map(task -> task.input);
        }

        @Override public Optional<AnalysisExecutionMetadata> execution(String id) {
            return Optional.ofNullable(byId.get(id)).map(task -> task.execution);
        }

        @Override public int markSucceeded(String id, String resultJson, long durationMs,
                                           DiagnosticModelResponse modelResponse) {
            StoredTask task = byId.get(id);
            if (task == null || !"RUNNING".equals(task.status)) return 0;
            try {
                task.result = mapper.readValue(resultJson, DiagnosticResult.class);
            } catch (JacksonException e) {
                throw new IllegalStateException(e);
            }
            task.status = "SUCCEEDED";
            task.failureReason = null;
            task.durationMs = durationMs;
            return 1;
        }

        @Override public void markFailed(String id, String failure, long durationMs) {
            StoredTask task = byId.get(id);
            if (task != null && ("RUNNING".equals(task.status) || "QUEUED".equals(task.status))) {
                task.status = "FAILED";
                task.failureReason = failure;
                task.durationMs = durationMs;
            }
        }

        @Override public synchronized int retry(String mchNo, String id) {
            StoredTask task = byId.get(id);
            if (task == null || !task.mchNo.equals(mchNo) || !"FAILED".equals(task.status)) return 0;
            task.status = "QUEUED";
            task.failureReason = null;
            return 1;
        }

        private int size() { return byId.size(); }

        private static final class StoredTask {
            private final String id;
            private final String mchNo;
            private final String tradeNo;
            private final DiagnosticInput input;
            private final AnalysisExecutionMetadata execution;
            private volatile String status = "QUEUED";
            private volatile DiagnosticResult result;
            private volatile String failureReason;
            private volatile Long durationMs;

            private StoredTask(String id, String mchNo, String tradeNo, String reuseKey, DiagnosticInput input,
                               String snapshotHash, String modelId, String modelConfigurationId,
                               String promptVersion, String outputSchemaVersion, String knowledgeVersion) {
                this.id = id;
                this.mchNo = mchNo;
                this.tradeNo = tradeNo;
                this.input = input;
                this.execution = new AnalysisExecutionMetadata(OffsetDateTime.now(), snapshotHash, modelId,
                        modelConfigurationId, promptVersion, outputSchemaVersion, knowledgeVersion, List.of(), false);
            }

            private AnalysisTask toTask() {
                return new AnalysisTask(id, tradeNo, status, result, failureReason, durationMs, false, execution, List.of());
            }
        }
    }
}
