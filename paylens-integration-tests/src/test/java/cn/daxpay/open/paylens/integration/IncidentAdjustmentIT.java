package cn.daxpay.open.paylens.integration;

import cn.daxpay.open.paylens.demo.IncidentVersionConflictException;
import cn.daxpay.open.paylens.demo.PayLensIncidentAdjustmentService;
import cn.daxpay.open.paylens.analysis.IncidentAnalysisRepository;
import cn.daxpay.open.paylens.incidents.domain.Observation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentAdjustmentIT {
    private static final String DEMO_MERCHANT = "M-PAYLENS-TEST";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static boolean startedTestcontainersPostgres;
    private static JdbcTemplate jdbc;
    private static ObjectMapper mapper;
    private static PayLensIncidentAdjustmentService service;
    private static IncidentAnalysisRepository analysisRepository;

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
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(jdbcUrl);
        source.setUsername(username);
        source.setPassword(password);
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V003__incident_workbench.sql"));
        schema.execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pay_trade (
                  mch_no varchar(64) NOT NULL, trade_no varchar(64) NOT NULL, amount bigint,
                  currency varchar(3), status varchar(16), deleted boolean NOT NULL DEFAULT false,
                  PRIMARY KEY(mch_no,trade_no))
                """);
        mapper = JsonMapper.builder().findAndAddModules().build();
        var transactionManager = new DataSourceTransactionManager(source);
        service = new PayLensIncidentAdjustmentService(jdbc, mapper,
                transactionManager, DEMO_MERCHANT);
        analysisRepository = new IncidentAnalysisRepository(jdbc, mapper, transactionManager);
    }

    @AfterAll
    static void stopDisposableDatabase() {
        if (startedTestcontainersPostgres) POSTGRES.stop();
    }

    @BeforeEach
    void clearOnlyTheEphemeralContainerTables() {
        jdbc.execute("TRUNCATE paylens_incident_analysis_attempt,paylens_incident_analysis,paylens_embedding_cache,paylens_incident_revision,paylens_incident_member,paylens_incident,paylens_incident_batch,pay_trade RESTART IDENTITY CASCADE");
    }

    @Test
    void splitRequiresACompletePartitionAndKeepsVersionedSnapshots() throws Exception {
        createBatchAndIncident("I-SPLIT", "B-SPLIT", 1, List.of(observation("O-1", "T-1"), observation("O-2", "T-2")));

        var result = service.split("I-SPLIT", 1, List.of(List.of("O-1"), List.of("O-2")), "Separate query paths", "user-7");

        assertEquals(2, result.incidentIds().size());
        assertEquals(List.of(1, 1), result.memberCounts());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM paylens_incident WHERE id='I-SPLIT'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_revision WHERE incident_id='I-SPLIT' AND version=1", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT snapshot_json::text FROM paylens_incident_revision WHERE incident_id='I-SPLIT' AND version=1", String.class).contains("O-2"));
        assertThrows(IncidentVersionConflictException.class,
                () -> service.split("I-SPLIT", 1, List.of(List.of("O-1"), List.of("O-2")), "stale version", "user-7"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_member WHERE incident_id='I-SPLIT'", Integer.class));
    }

    @Test
    void mergeArchivesSourceAndRecalculatesDistinctTradeStatistics() throws Exception {
        createBatchAndIncident("I-A", "B-MERGE", 1, List.of(observation("O-A", "T-A")));
        createIncident("I-B", "B-MERGE", 2, List.of(observation("O-B", "T-B")));
        jdbc.update("INSERT INTO pay_trade(mch_no,trade_no,amount,currency,status) VALUES (?,?,?,?,?)",
                DEMO_MERCHANT, "T-A", 120L, "CNY", "success");
        jdbc.update("INSERT INTO pay_trade(mch_no,trade_no,amount,currency,status) VALUES (?,?,?,?,?)",
                DEMO_MERCHANT, "T-B", 300L, "CNY", "processing");

        var result = service.merge(List.of(new PayLensIncidentAdjustmentService.VersionRef("I-B", 1),
                new PayLensIncidentAdjustmentService.VersionRef("I-A", 1)), "Same verified incident", "user-7");

        assertEquals(2, result.memberCount());
        assertEquals("ARCHIVED", jdbc.queryForObject("SELECT review_status FROM paylens_incident WHERE id='I-B'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_member WHERE incident_id=?", Integer.class, result.incidentId()));
        String stats = jdbc.queryForObject("SELECT statistics_json::text FROM paylens_incident WHERE id=?", String.class, result.incidentId());
        assertTrue(stats.contains("420"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_revision WHERE incident_id='I-B' AND operation='MERGE'", Integer.class));
    }

    @Test
    void mergeRejectsDifferentBusinessBoundariesWithoutChangingMembership() throws Exception {
        createBatchAndIncident("I-C", "B-BOUNDARY", 1, List.of(observation("O-C", "T-C")));
        createIncident("I-D", "B-BOUNDARY", 2, List.of(new Observation("O-D", "T-D", DEMO_MERCHANT,
                "REFUND", "QUERY", "SIM", "TIMEOUT", "E-TIMEOUT", Instant.parse("2026-09-26T10:00:01Z"), "query timeout")));

        assertThrows(IllegalArgumentException.class, () -> service.merge(List.of(
                new PayLensIncidentAdjustmentService.VersionRef("I-C", 1),
                new PayLensIncidentAdjustmentService.VersionRef("I-D", 1)), "wrong boundaries", "user-7"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_member WHERE incident_id='I-C'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paylens_incident_member WHERE incident_id='I-D'", Integer.class));
    }

    @Test
    void refreshReadsCurrentTradeStatusWithoutChangingTheIncidentVersion() throws Exception {
        createBatchAndIncident("I-REFRESH", "B-REFRESH", 1, List.of(observation("O-R", "T-R")));
        jdbc.update("INSERT INTO pay_trade(mch_no,trade_no,amount,currency,status) VALUES (?,?,?,?,?)",
                DEMO_MERCHANT, "T-R", 100L, "CNY", "success");

        var progress = service.refresh("I-REFRESH");

        assertEquals(1, progress.distinctTradeCount());
        assertEquals(1L, progress.currentStatusDistribution().get("success"));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM paylens_incident WHERE id='I-REFRESH'", Integer.class));
    }

    @Test
    void eventAnalysisClaimReadsModelIdFromImmutableExecutionSnapshot() throws Exception {
        createBatchAndIncident("I-ANALYSIS", "B-ANALYSIS", 1, List.of(observation("O-ANALYSIS", "T-ANALYSIS")));
        String versions = """
                {"snapshotAt":"2026-09-26T10:00:00Z","snapshotHash":"snapshot-hash",
                 "modelId":"mock-event-model","modelConfigurationId":"config-hash",
                 "promptVersion":"prompt-hash","outputSchemaVersion":"schema-v1",
                 "knowledgeVersion":"knowledge-v1","legacy":false}
                """;
        jdbc.update("""
                INSERT INTO paylens_incident_analysis(id,mch_no,incident_id,incident_version,reuse_key,evidence_hash,
                  evidence_snapshot,execution_versions,retrieved_knowledge,status)
                VALUES ('A-ANALYSIS',?,?,1,'reuse-analysis','snapshot-hash',?::jsonb,?::jsonb,'[]'::jsonb,'QUEUED')
                """, DEMO_MERCHANT, "I-ANALYSIS", "{\"statistics\":{},\"evidence\":[],\"omittedEvidenceCount\":0}", versions);

        assertEquals("A-ANALYSIS", analysisRepository.claimNext().orElseThrow());
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM paylens_incident_analysis WHERE id='A-ANALYSIS'", String.class));
        assertEquals("mock-event-model", jdbc.queryForObject("SELECT model_id FROM paylens_incident_analysis_attempt WHERE analysis_id='A-ANALYSIS'", String.class));
    }

    private void createBatchAndIncident(String incidentId, String batchId, int groupNo,
                                        List<Observation> observations) throws Exception {
        jdbc.update("INSERT INTO paylens_incident_batch(id,mch_no,idempotency_key,scan_from,scan_to,status,execution_mode) VALUES (?,?,?,?,?,'SUCCEEDED','RULE_ONLY')",
                batchId, DEMO_MERCHANT, "idem-" + batchId, offset(Instant.parse("2026-09-26T09:59:00Z")),
                offset(Instant.parse("2026-09-26T10:01:00Z")));
        createIncident(incidentId, batchId, groupNo, observations);
    }

    private void createIncident(String incidentId, String batchId, int groupNo, List<Observation> observations) throws Exception {
        jdbc.update("""
                INSERT INTO paylens_incident(id,batch_id,mch_no,group_no,version,review_status,execution_mode,
                  algorithm_version,grouping_evidence,statistics_json)
                VALUES (?,?,?, ?,1,'OPEN','RULE_ONLY','rules-v1','{}'::jsonb,'{}'::jsonb)
                """, incidentId, batchId, DEMO_MERCHANT, groupNo);
        for (Observation observation : observations) {
            jdbc.update("INSERT INTO paylens_incident_member(batch_id,incident_id,observation_id,observation_json) VALUES (?,?,?,?::jsonb)",
                    batchId, incidentId, observation.id(), mapper.writeValueAsString(observation));
        }
    }

    private Observation observation(String id, String tradeNo) {
        return new Observation(id, tradeNo, DEMO_MERCHANT, "PAY", "QUERY", "SIM", "TIMEOUT", "E-TIMEOUT",
                Instant.parse("2026-09-26T10:00:00Z"), "支付查单超时");
    }

    private OffsetDateTime offset(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
