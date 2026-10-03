package cn.daxpay.open.paylens.analysis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
public class IncidentAnalysisRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public IncidentAnalysisRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                      PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Optional<IncidentAnalysisTask> findByReuseKey(String mchNo, String reuseKey) {
        return jdbc.query("SELECT id FROM paylens_incident_analysis WHERE mch_no=? AND reuse_key=?",
                (rs, row) -> rs.getString("id"), mchNo, reuseKey).stream().findFirst().map(this::map);
    }

    public IncidentAnalysisTask insertIfAbsent(String mchNo, String incidentId, int version, String reuseKey,
                                                String evidenceHash, String evidence, String versions,
                                                String knowledge) {
        return transaction.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(5872026)");
            Optional<IncidentAnalysisTask> existing = findByReuseKey(mchNo, reuseKey);
            if (existing.isPresent()) return existing.get().withReused(true);
            Long count = jdbc.queryForObject("""
                    SELECT (SELECT count(*) FROM paylens_analysis_task WHERE status='QUEUED') +
                           (SELECT count(*) FROM paylens_incident_analysis WHERE status='QUEUED')
                    """, Long.class);
            if (count != null && count >= 50) throw new AnalysisQueueFullException();
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO paylens_incident_analysis(id,mch_no,incident_id,incident_version,reuse_key,
                      evidence_hash,evidence_snapshot,execution_versions,retrieved_knowledge,status)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,'QUEUED')
                    ON CONFLICT (reuse_key) DO NOTHING
                    """, id, mchNo, incidentId, version, reuseKey, evidenceHash, evidence, versions, knowledge);
            return findByReuseKey(mchNo, reuseKey).orElseThrow().withReused(false);
        });
    }

    public int queuedCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM paylens_incident_analysis WHERE status='QUEUED'", Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<String> claimNext() {
        return transaction.execute(status -> {
            Optional<String> id = jdbc.query("""
                    WITH next_task AS (
                      SELECT id FROM paylens_incident_analysis WHERE status='QUEUED'
                      ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE paylens_incident_analysis task SET status='RUNNING',updated_at=now()
                      FROM next_task WHERE task.id=next_task.id RETURNING task.id
                    """, (rs, row) -> rs.getString(1)).stream().findFirst();
            id.ifPresent(this::startAttempt);
            return id;
        });
    }

    private void startAttempt(String id) {
        jdbc.update("""
                INSERT INTO paylens_incident_analysis_attempt(analysis_id,attempt_no,status,started_at,model_id)
                SELECT ?,COALESCE(MAX(t.attempt_no),0)+1,'RUNNING',now(),LEFT(a.execution_versions->>'modelId',128)
                  FROM paylens_incident_analysis a LEFT JOIN paylens_incident_analysis_attempt t ON t.analysis_id=a.id
                 WHERE a.id=? GROUP BY a.id,(a.execution_versions->>'modelId')
                """, id, id);
    }

    public Optional<IncidentAnalysisInput> snapshot(String id) {
        return jdbc.query("SELECT evidence_snapshot FROM paylens_incident_analysis WHERE id=?", (rs, row) -> {
            try { return mapper.readValue(rs.getString(1), IncidentAnalysisInput.class); }
            catch (JacksonException exception) { throw new IllegalStateException("事件分析快照无法读取", exception); }
        }, id).stream().findFirst();
    }

    public Optional<IncidentAnalysisExecution> execution(String id) {
        return jdbc.query("SELECT execution_versions,retrieved_knowledge FROM paylens_incident_analysis WHERE id=?",
                (rs, row) -> {
                    try {
                        VersionSnapshot versions = mapper.readValue(rs.getString("execution_versions"), VersionSnapshot.class);
                        List<KnowledgeChunk> knowledge = mapper.readValue(rs.getString("retrieved_knowledge"),
                                mapper.getTypeFactory().constructCollectionType(List.class, KnowledgeChunk.class));
                        return new IncidentAnalysisExecution(versions.snapshotAt(), versions.snapshotHash(), versions.modelId(),
                                versions.modelConfigurationId(), versions.promptVersion(), versions.outputSchemaVersion(),
                                versions.knowledgeVersion(), knowledge, versions.legacy());
                    } catch (JacksonException exception) { throw new IllegalStateException("事件分析版本无法读取", exception); }
                }, id).stream().findFirst();
    }

    public List<KnowledgeChunk> retrievedKnowledge(String id) {
        return execution(id).map(IncidentAnalysisExecution::retrievedKnowledge).orElse(List.of());
    }

    public int markSucceeded(String id, String result, long duration, DiagnosticModelResponse response) {
        int changed = jdbc.update("UPDATE paylens_incident_analysis SET status='SUCCEEDED',result_json=?::jsonb,failure_code=NULL,duration_ms=?,updated_at=now() WHERE id=? AND status='RUNNING'",
                result, duration, id);
        finishAttempt(id, "SUCCEEDED", null, duration, response);
        return changed;
    }

    public void markFailed(String id, String code, long duration) {
        String stableCode = code == null ? "INCIDENT_ANALYSIS_FAILED" : code.substring(0, Math.min(128, code.length()));
        jdbc.update("UPDATE paylens_incident_analysis SET status='FAILED',failure_code=?,duration_ms=?,updated_at=now() WHERE id=? AND status IN ('RUNNING','QUEUED')",
                stableCode, duration, id);
        finishAttempt(id, "FAILED", stableCode, duration, null);
    }

    private void finishAttempt(String id, String state, String failure, long duration, DiagnosticModelResponse response) {
        jdbc.update("""
                UPDATE paylens_incident_analysis_attempt SET status=?,completed_at=now(),duration_ms=?,failure_code=?,
                       prompt_tokens=?,completion_tokens=?
                 WHERE id=(SELECT id FROM paylens_incident_analysis_attempt WHERE analysis_id=? AND status='RUNNING' ORDER BY attempt_no DESC LIMIT 1)
                """, state, duration, failure, response == null ? null : response.promptTokens(),
                response == null ? null : response.completionTokens(), id);
    }

    public int retry(String mchNo, String id) {
        return transaction.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(5872026)");
            Long count = jdbc.queryForObject("""
                    SELECT (SELECT count(*) FROM paylens_analysis_task WHERE status='QUEUED') +
                           (SELECT count(*) FROM paylens_incident_analysis WHERE status='QUEUED')
                    """, Long.class);
            if (count != null && count >= 50) throw new AnalysisQueueFullException();
            return jdbc.update("UPDATE paylens_incident_analysis SET status='QUEUED',failure_code=NULL,duration_ms=NULL,updated_at=now() WHERE mch_no=? AND id=? AND status='FAILED'",
                    mchNo, id);
        });
    }

    public int markInterruptedRunningFailed() {
        int changed = jdbc.update("UPDATE paylens_incident_analysis SET status='FAILED',failure_code='PROCESS_RESTARTED',updated_at=now() WHERE status='RUNNING'");
        jdbc.update("UPDATE paylens_incident_analysis_attempt SET status='FAILED',failure_code='PROCESS_RESTARTED',completed_at=now() WHERE status='RUNNING'");
        return changed;
    }

    public IncidentAnalysisTask findById(String mchNo, String id) {
        return jdbc.query("SELECT id FROM paylens_incident_analysis WHERE mch_no=? AND id=?",
                (rs, row) -> rs.getString(1), mchNo, id).stream()
                .findFirst().map(this::map)
                .orElseThrow(() -> new IllegalArgumentException("事件分析任务不存在或无权访问"));
    }

    public List<IncidentAnalysisTask> history(String mchNo, String incidentId, int page, int pageSize) {
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<String> ids = jdbc.query("""
                SELECT id FROM paylens_incident_analysis WHERE mch_no=? AND incident_id=?
                 ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> rs.getString(1), mchNo, incidentId, pageSize, offset);
        return ids.stream().map(id -> findById(mchNo, id)).toList();
    }

    public long historyCount(String mchNo, String incidentId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM paylens_incident_analysis WHERE mch_no=? AND incident_id=?",
                Long.class, mchNo, incidentId);
        return count == null ? 0 : count;
    }

    public boolean hasQueued() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM paylens_incident_analysis WHERE status='QUEUED'", Integer.class);
        return count != null && count > 0;
    }

    private IncidentAnalysisTask map(String id) {
        AnalysisRow row = jdbc.query("SELECT incident_id,incident_version,status,result_json,failure_code,duration_ms FROM paylens_incident_analysis WHERE id=?",
                (rs, index) -> {
                    IncidentAnalysisResult result = null;
                    try {
                        if (rs.getString("result_json") != null) {
                            result = mapper.readValue(rs.getString("result_json"), IncidentAnalysisResult.class);
                        }
                    } catch (JacksonException exception) {
                        throw new IllegalStateException("事件分析结果无法读取", exception);
                    }
                    return new AnalysisRow(rs.getString("incident_id"), rs.getInt("incident_version"),
                            rs.getString("status"), result, rs.getString("failure_code"),
                            rs.getObject("duration_ms", Long.class));
                }, id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("事件分析任务不存在"));
        IncidentAnalysisExecution execution = execution(id).orElse(null);
        List<IncidentAnalysisAttempt> attempts = jdbc.query("""
                SELECT attempt_no,status,started_at,completed_at,duration_ms,failure_code,model_id,prompt_tokens,completion_tokens
                  FROM paylens_incident_analysis_attempt WHERE analysis_id=? ORDER BY attempt_no
                """, (attempt, row2) -> new IncidentAnalysisAttempt(attempt.getInt("attempt_no"),
                attempt.getString("status"), attempt.getObject("started_at", OffsetDateTime.class),
                attempt.getObject("completed_at", OffsetDateTime.class), attempt.getObject("duration_ms", Long.class),
                attempt.getString("failure_code"), attempt.getString("model_id"),
                attempt.getObject("prompt_tokens", Long.class), attempt.getObject("completion_tokens", Long.class)), id);
        return new IncidentAnalysisTask(id, row.incidentId(), row.incidentVersion(), row.status(), row.result(),
                row.failureCode(), row.durationMs(), false, execution, attempts);
    }

    private record AnalysisRow(String incidentId, int incidentVersion, String status, IncidentAnalysisResult result,
                               String failureCode, Long durationMs) {}

    private record VersionSnapshot(OffsetDateTime snapshotAt, String snapshotHash, String modelId,
                                   String modelConfigurationId, String promptVersion, String outputSchemaVersion,
                                   String knowledgeVersion, boolean legacy) {}
}
