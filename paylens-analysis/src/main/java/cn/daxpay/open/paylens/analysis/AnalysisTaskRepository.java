package cn.daxpay.open.paylens.analysis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

@Repository
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
public class AnalysisTaskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AnalysisTaskRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS paylens_analysis_task (
                  id varchar(36) PRIMARY KEY,
                  mch_no varchar(64) NOT NULL,
                  trade_no varchar(64) NOT NULL,
                  reuse_key varchar(64) NOT NULL UNIQUE,
                  status varchar(16) NOT NULL,
                  snapshot jsonb NOT NULL,
                  result_json jsonb,
                  failure_reason varchar(500),
                  duration_ms bigint,
                  created_at timestamptz NOT NULL DEFAULT now(),
                  updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_paylens_analysis_mch_trade ON paylens_analysis_task(mch_no, trade_no, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_paylens_analysis_queue ON paylens_analysis_task(status, created_at)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS snapshot_hash varchar(64)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS snapshot_at timestamptz");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS model_id varchar(128)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS model_configuration_id varchar(64)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS prompt_version varchar(128)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS output_schema_version varchar(64)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS knowledge_version varchar(64)");
        jdbc.execute("ALTER TABLE paylens_analysis_task ADD COLUMN IF NOT EXISTS retrieved_knowledge jsonb");
        jdbc.execute("CREATE TABLE IF NOT EXISTS paylens_analysis_attempt (" +
                "id bigserial PRIMARY KEY, task_id varchar(36) NOT NULL, attempt_no integer NOT NULL, " +
                "status varchar(16) NOT NULL, started_at timestamptz NOT NULL, completed_at timestamptz, " +
                "duration_ms bigint, failure_code varchar(128), model_id varchar(128), " +
                "prompt_version varchar(128), knowledge_version varchar(64), UNIQUE(task_id,attempt_no))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_paylens_attempt_task ON paylens_analysis_attempt(task_id,attempt_no)");
        jdbc.execute("ALTER TABLE paylens_analysis_attempt ADD COLUMN IF NOT EXISTS returned_model_id varchar(128)");
        jdbc.execute("ALTER TABLE paylens_analysis_attempt ADD COLUMN IF NOT EXISTS prompt_tokens bigint");
        jdbc.execute("ALTER TABLE paylens_analysis_attempt ADD COLUMN IF NOT EXISTS completion_tokens bigint");
        jdbc.execute("CREATE TABLE IF NOT EXISTS paylens_schema_migration (version varchar(32) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
        jdbc.update("INSERT INTO paylens_schema_migration(version) VALUES ('V002') ON CONFLICT DO NOTHING");
    }

    public Optional<AnalysisTask> findByReuseKey(String mchNo, String reuseKey) {
        return jdbc.query("SELECT id FROM paylens_analysis_task WHERE mch_no=? AND reuse_key=?",
                (rs, row) -> rs.getString(1), mchNo, reuseKey).stream().findFirst().map(this::map);
    }

    @Transactional
    public AnalysisTask insertIfAbsent(String mchNo, String tradeNo, String reuseKey, String snapshot,
                                       String snapshotHash, String modelId, String modelConfigurationId,
                                       String promptVersion, String outputSchemaVersion, String knowledgeVersion) {
        // Serialize admission checks so concurrent submissions cannot exceed the 50-task database backlog.
        jdbc.execute("SELECT pg_advisory_xact_lock(5872026)");
        Optional<AnalysisTask> existing = findByReuseKey(mchNo, reuseKey);
        if (existing.isPresent()) return existing.get().withReused(true);
        if (queuedCount() >= 50) throw new AnalysisQueueFullException();
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO paylens_analysis_task(id,mch_no,trade_no,reuse_key,status,snapshot,snapshot_hash,snapshot_at,
                    model_id,model_configuration_id,prompt_version,output_schema_version,knowledge_version)
                VALUES (?,?,?,?,'QUEUED',?::jsonb,?,now(),?,?,?,?,?) ON CONFLICT (reuse_key) DO NOTHING
                """, id, mchNo, tradeNo, reuseKey, snapshot, snapshotHash, modelId,
                modelConfigurationId, promptVersion, outputSchemaVersion, knowledgeVersion);
        AnalysisTask task = findByReuseKey(mchNo, reuseKey).orElseThrow();
        return inserted == 0 ? task.withReused(true) : task;
    }

    public int queuedCount() {
        return queuedSingleCount() + queuedIncidentCount();
    }

    public int queuedSingleCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM paylens_analysis_task WHERE status='QUEUED'", Integer.class);
        return count == null ? 0 : count;
    }

    private int queuedIncidentCount() {
        Integer hasIncidentQueue = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='paylens_incident_analysis'", Integer.class);
        return hasIncidentQueue != null && hasIncidentQueue > 0
                ? jdbc.queryForObject("SELECT count(*) FROM paylens_incident_analysis WHERE status='QUEUED'", Integer.class)
                : 0;
    }

    public int transitionToRunning(String id) {
        return jdbc.update("UPDATE paylens_analysis_task SET status='RUNNING',updated_at=now() WHERE id=? AND status='QUEUED'", id);
    }

    /** Atomically claims one queued task across all application workers. */
    @Transactional
    public Optional<String> claimNext() {
        Optional<String> claimed = jdbc.query("""
                WITH next_task AS (
                  SELECT id FROM paylens_analysis_task WHERE status='QUEUED'
                  ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE paylens_analysis_task task
                   SET status='RUNNING',updated_at=now()
                  FROM next_task WHERE task.id=next_task.id
                RETURNING task.id
                """, (rs, row) -> rs.getString(1)).stream().findFirst();
        claimed.ifPresent(this::startAttempt);
        return claimed;
    }

    private void startAttempt(String taskId) {
        jdbc.update("""
                INSERT INTO paylens_analysis_attempt(task_id,attempt_no,status,started_at,model_id,prompt_version,knowledge_version)
                SELECT ?, COALESCE(MAX(attempt_no),0)+1, 'RUNNING', now(), t.model_id,t.prompt_version,t.knowledge_version
                  FROM paylens_analysis_task t LEFT JOIN paylens_analysis_attempt a ON a.task_id=t.id
                 WHERE t.id=? GROUP BY t.id,t.model_id,t.prompt_version,t.knowledge_version
                """, taskId, taskId);
    }

    public void storeRetrievedKnowledge(String id, String knowledgeJson) {
        jdbc.update("UPDATE paylens_analysis_task SET retrieved_knowledge=?::jsonb,updated_at=now() WHERE id=? AND status='RUNNING'",
                knowledgeJson, id);
    }

    public Optional<DiagnosticInput> snapshot(String id) {
        return jdbc.query("SELECT snapshot FROM paylens_analysis_task WHERE id=?", (rs, row) -> {
            try { return mapper.readValue(rs.getString(1), DiagnosticInput.class); }
            catch (JacksonException e) { throw new IllegalStateException("已保存的分析快照无法读取", e); }
        }, id).stream().findFirst();
    }

    public Optional<AnalysisExecutionMetadata> execution(String id) {
        return readExecution(id);
    }

    public Optional<AnalysisTask> findById(String mchNo, String id) {
        return jdbc.query("SELECT id FROM paylens_analysis_task WHERE mch_no=? AND id=?",
                (rs, row) -> rs.getString(1), mchNo, id).stream().findFirst().map(this::map);
    }

    public List<AnalysisTask> history(String mchNo, String tradeNo, int page, int pageSize) {
        int offset = Math.multiplyExact(page - 1, pageSize);
        return jdbc.query("SELECT id FROM paylens_analysis_task WHERE mch_no=? AND trade_no=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, row) -> rs.getString(1), mchNo, tradeNo, pageSize, offset).stream().map(this::map).toList();
    }

    public long historyCount(String mchNo, String tradeNo) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM paylens_analysis_task WHERE mch_no=? AND trade_no=?",
                Long.class, mchNo, tradeNo);
        return count == null ? 0 : count;
    }

    public List<String> queuedIds(int limit) {
        return jdbc.query("SELECT id FROM paylens_analysis_task WHERE status='QUEUED' ORDER BY created_at LIMIT ?",
                (rs, row) -> rs.getString(1), limit);
    }

    public int markSucceeded(String id, String result, long durationMs, DiagnosticModelResponse modelResponse) {
        int updated = jdbc.update("UPDATE paylens_analysis_task SET status='SUCCEEDED',result_json=?::jsonb,failure_reason=NULL,duration_ms=?,updated_at=now() WHERE id=? AND status='RUNNING'",
                result, durationMs, id);
        finishAttempt(id, "SUCCEEDED", null, durationMs, modelResponse);
        return updated;
    }

    public void markFailed(String id, String failure, long durationMs) {
        jdbc.update("UPDATE paylens_analysis_task SET status='FAILED',failure_reason=?,duration_ms=?,updated_at=now() WHERE id=? AND status IN ('RUNNING','QUEUED')",
                failure.substring(0, Math.min(500, failure.length())), durationMs, id);
        finishAttempt(id, "FAILED", failure, durationMs, null);
    }

    private void finishAttempt(String id, String status, String failure, long durationMs, DiagnosticModelResponse response) {
        jdbc.update("""
                UPDATE paylens_analysis_attempt SET status=?,completed_at=now(),duration_ms=?,failure_code=?,
                       returned_model_id=?,prompt_tokens=?,completion_tokens=?
                 WHERE id=(SELECT id FROM paylens_analysis_attempt WHERE task_id=? AND status='RUNNING' ORDER BY attempt_no DESC LIMIT 1)
                """, status, durationMs, failure == null ? null : failure.substring(0, Math.min(128, failure.length())),
                response == null ? null : response.modelId(), response == null ? null : response.promptTokens(),
                response == null ? null : response.completionTokens(), id);
    }

    @Transactional
    public int retry(String mchNo, String id) {
        jdbc.execute("SELECT pg_advisory_xact_lock(5872026)");
        if (queuedCount() >= 50) throw new AnalysisQueueFullException();
        return jdbc.update("UPDATE paylens_analysis_task SET status='QUEUED',result_json=NULL,failure_reason=NULL,duration_ms=NULL,updated_at=now() WHERE mch_no=? AND id=? AND status='FAILED'",
                mchNo, id);
    }

    public int markInterruptedRunningFailed() {
        int updated = jdbc.update("UPDATE paylens_analysis_task SET status='FAILED',failure_reason='PROCESS_RESTARTED',updated_at=now() WHERE status='RUNNING'");
        jdbc.update("UPDATE paylens_analysis_attempt SET status='FAILED',failure_code='PROCESS_RESTARTED',completed_at=now() WHERE status='RUNNING'");
        return updated;
    }

    private AnalysisTask map(String id) {
        TaskRow taskRow = jdbc.query("SELECT trade_no,status,result_json,failure_reason,duration_ms FROM paylens_analysis_task WHERE id=?",
                (rs, index) -> new TaskRow(rs.getString("trade_no"), rs.getString("status"),
                        rs.getString("result_json"), rs.getString("failure_reason"),
                        rs.getObject("duration_ms", Long.class)), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分析任务不存在"));
        DiagnosticResult result = null;
        if (taskRow.resultJson() != null) {
            try { result = mapper.readValue(taskRow.resultJson(), DiagnosticResult.class); }
            catch (JacksonException e) { throw new IllegalStateException("已保存的诊断结果无法读取", e); }
        }
        AnalysisExecutionMetadata execution = readExecution(id).orElse(null);
        List<AnalysisAttempt> attempts = jdbc.query("SELECT attempt_no,status,started_at,completed_at,duration_ms,failure_code,model_id,returned_model_id,prompt_tokens,completion_tokens,prompt_version,knowledge_version FROM paylens_analysis_attempt WHERE task_id=? ORDER BY attempt_no",
                (rs, row) -> new AnalysisAttempt(rs.getInt("attempt_no"), rs.getString("status"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                        rs.getObject("duration_ms", Long.class), rs.getString("failure_code"), rs.getString("model_id"),
                        rs.getString("returned_model_id"), rs.getObject("prompt_tokens", Long.class),
                        rs.getObject("completion_tokens", Long.class), rs.getString("prompt_version"),
                        rs.getString("knowledge_version")), id);
        return new AnalysisTask(id, taskRow.tradeNo(), taskRow.status(), result, taskRow.failure(), taskRow.duration(), false, execution, attempts);
    }

    private record TaskRow(String tradeNo, String status, String resultJson, String failure, Long duration) {}

    private Optional<AnalysisExecutionMetadata> readExecution(String id) {
        try {
            return jdbc.query("SELECT snapshot_at,snapshot_hash,model_id,model_configuration_id,prompt_version,output_schema_version,knowledge_version,retrieved_knowledge " +
                            "FROM paylens_analysis_task WHERE id=?", (rs, row) -> {
                        List<KnowledgeChunk> chunks = rs.getString("retrieved_knowledge") == null ? List.of() :
                                mapper.readValue(rs.getString("retrieved_knowledge"), mapper.getTypeFactory().constructCollectionType(List.class, KnowledgeChunk.class));
                        boolean legacy = rs.getString("model_configuration_id") == null || rs.getString("snapshot_hash") == null;
                        return new AnalysisExecutionMetadata(rs.getObject("snapshot_at", OffsetDateTime.class),
                                rs.getString("snapshot_hash"), rs.getString("model_id"),
                                rs.getString("model_configuration_id"), rs.getString("prompt_version"),
                                rs.getString("output_schema_version"), rs.getString("knowledge_version"), chunks, legacy);
                    }, id).stream().findFirst();
        } catch (JacksonException e) { throw new IllegalStateException("分析版本信息无法读取", e); }
    }
}
