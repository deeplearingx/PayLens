package cn.daxpay.open.paylens.integration;

import cn.daxpay.open.paylens.analysis.AnalysisTask;
import cn.daxpay.open.paylens.analysis.AnalysisTaskRepository;
import cn.daxpay.open.paylens.analysis.AnalysisQueueFullException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisTaskRepositoryIT {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static boolean startedTestcontainersPostgres;

    private static AnnotationConfigApplicationContext context;
    private static AnalysisTaskRepository repository;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connectToDisposableDatabase() {
        String jdbcUrl = System.getenv("PAYLENS_TEST_JDBC_URL");
        String username = System.getenv("PAYLENS_TEST_JDBC_USER");
        String password = System.getenv("PAYLENS_TEST_JDBC_PASSWORD");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            try {
                POSTGRES.start();
                startedTestcontainersPostgres = true;
            } catch (RuntimeException dockerUnavailable) {
                Assumptions.abort("No usable Docker API for Testcontainers; configure PAYLENS_TEST_JDBC_URL to use an isolated PostgreSQL instance.");
            }
            jdbcUrl = POSTGRES.getJdbcUrl();
            username = POSTGRES.getUsername();
            password = POSTGRES.getPassword();
        }
        if (username == null || username.isBlank() || password == null) {
            throw new IllegalStateException("PAYLENS_TEST_JDBC_USER and PAYLENS_TEST_JDBC_PASSWORD are required with PAYLENS_TEST_JDBC_URL.");
        }
        System.setProperty("paylens.test.jdbc-url", jdbcUrl);
        System.setProperty("paylens.test.jdbc-user", username);
        System.setProperty("paylens.test.jdbc-password", password);
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        repository = context.getBean(AnalysisTaskRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        repository.initialize();
        jdbc.execute("CREATE TABLE IF NOT EXISTS paylens_incident_analysis (id varchar(36) PRIMARY KEY, status varchar(16) NOT NULL)");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
        System.clearProperty("paylens.test.jdbc-url");
        System.clearProperty("paylens.test.jdbc-user");
        System.clearProperty("paylens.test.jdbc-password");
        if (startedTestcontainersPostgres) POSTGRES.stop();
    }

    @BeforeEach
    void clearOnlyTheEphemeralContainerTables() {
        jdbc.execute("TRUNCATE paylens_analysis_attempt, paylens_analysis_task, paylens_incident_analysis");
    }

    @Test
    void oneHundredConcurrentSameKeySubmissionsCreateOneDurableTask() throws Exception {
        int callerCount = 100;
        int concurrentCallers = 24;
        CountDownLatch ready = new CountDownLatch(concurrentCallers);
        CountDownLatch start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(concurrentCallers)) {
            List<java.util.concurrent.Future<AnalysisTask>> futures = new ArrayList<>();
            for (int i = 0; i < callerCount; i++) {
                futures.add(callers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                    return repository.insertIfAbsent("M-DEMO", "T-CONCURRENT", "same-reuse-key", snapshot(),
                            "snapshot-hash", "fake-model", "model-config-hash", "prompt-hash", "schema-v1", "kb-v1");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<String> ids = new java.util.HashSet<>();
            for (var future : futures) ids.add(future.get(30, TimeUnit.SECONDS).id());
            assertEquals(1, ids.size());
            assertEquals(1, repository.queuedCount());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paylens_analysis_task", Integer.class));
        }
    }

    @Test
    void fiftyTaskQueueIsBoundedAndClaimsAreAtomic() throws Exception {
        for (int i = 0; i < 50; i++) insert("T-" + i, "reuse-" + i);
        assertThrows(AnalysisQueueFullException.class, () -> insert("T-OVERFLOW", "reuse-overflow"));

        try (var callers = Executors.newFixedThreadPool(24)) {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) futures.add(callers.submit(() -> repository.claimNext().orElse(null)));
            Set<String> claimed = new java.util.HashSet<>();
            for (var future : futures) {
                String id = future.get(30, TimeUnit.SECONDS);
                if (id != null) claimed.add(id);
            }
            assertEquals(50, claimed.size());
            assertEquals(0, repository.queuedCount());
        }
    }

    @Test
    void singleAndIncidentAnalysisShareTheSameFiftyTaskAdmissionLimit() {
        for (int i = 0; i < 30; i++) {
            jdbc.update("INSERT INTO paylens_incident_analysis(id,status) VALUES (?, 'QUEUED')", "INC-" + i);
        }
        for (int i = 0; i < 20; i++) insert("T-SHARED-" + i, "shared-reuse-" + i);

        assertEquals(50, repository.queuedCount());
        assertThrows(AnalysisQueueFullException.class, () -> insert("T-SHARED-OVERFLOW", "shared-overflow"));
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM paylens_analysis_task WHERE status='QUEUED'", Integer.class));
        assertEquals(30, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_analysis WHERE status='QUEUED'", Integer.class));
    }

    @Test
    void failedAttemptsRemainAfterExplicitRetry() {
        AnalysisTask queued = insert("T-RETRY", "retry-key");
        String taskId = repository.claimNext().orElseThrow();
        repository.markFailed(taskId, "MODEL_TIMEOUT", 321);
        assertEquals(1, repository.retry("M-DEMO", taskId));
        assertEquals(taskId, repository.claimNext().orElseThrow());
        repository.markFailed(taskId, "MODEL_RATE_LIMITED", 654);

        AnalysisTask failed = repository.findById("M-DEMO", queued.id()).orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals(2, failed.attempts().size());
        assertEquals("MODEL_TIMEOUT", failed.attempts().getFirst().failureCode());
        assertEquals("MODEL_RATE_LIMITED", failed.attempts().getLast().failureCode());
        assertNotNull(failed.execution().snapshotAt());
        assertEquals("snapshot-hash", failed.execution().snapshotHash());
    }

    @Test
    void restartFailsRunningAttemptAndLeavesQueuedWorkRecoverable() {
        AnalysisTask interrupted = insert("T-INTERRUPTED", "interrupted-key");
        String runningId = repository.claimNext().orElseThrow();
        assertEquals(interrupted.id(), runningId);
        AnalysisTask waiting = insert("T-WAITING", "waiting-key");

        assertEquals(1, repository.markInterruptedRunningFailed());
        AnalysisTask failed = repository.findById("M-DEMO", runningId).orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals("PROCESS_RESTARTED", failed.failureReason());
        assertEquals("PROCESS_RESTARTED", failed.attempts().getFirst().failureCode());
        assertEquals("QUEUED", repository.findById("M-DEMO", waiting.id()).orElseThrow().status());
        assertEquals(waiting.id(), repository.claimNext().orElseThrow());

        repository.initialize();
        repository.initialize();
    }

    private AnalysisTask insert(String tradeNo, String reuseKey) {
        return repository.insertIfAbsent("M-DEMO", tradeNo, reuseKey, snapshot(), "snapshot-hash",
                "fake-model", "model-config-hash", "prompt-hash", "schema-v1", "kb-v1");
    }

    private String snapshot() {
        return "{\"tradeNo\":\"T-1\",\"localStatus\":\"CLOSE\",\"channelStatus\":\"SUCCESS\",\"abnormalType\":\"close_paid\",\"evidence\":[]}";
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.postgresql.Driver");
            source.setUrl(System.getProperty("paylens.test.jdbc-url"));
            source.setUsername(System.getProperty("paylens.test.jdbc-user"));
            source.setPassword(System.getProperty("paylens.test.jdbc-password"));
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean AnalysisTaskRepository analysisTaskRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new AnalysisTaskRepository(jdbc, mapper);
        }
    }
}
