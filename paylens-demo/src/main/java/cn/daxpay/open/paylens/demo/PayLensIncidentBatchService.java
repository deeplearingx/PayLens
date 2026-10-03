package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.incidents.domain.CompleteLinkGrouper;
import cn.daxpay.open.paylens.incidents.domain.EventGroup;
import cn.daxpay.open.paylens.incidents.domain.GroupingPolicy;
import cn.daxpay.open.paylens.incidents.domain.GroupingResult;
import cn.daxpay.open.paylens.incidents.domain.Observation;
import cn.daxpay.open.paylens.incidents.domain.SimilarityProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.event.EventListener;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
public class PayLensIncidentBatchService {
    private static final Logger log = LoggerFactory.getLogger(PayLensIncidentBatchService.class);
    private static final int MAX_OBSERVATIONS = 500;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PayLensIncidentObservationReader observationReader;
    private final PayLensIncidentSimilarityProvider semanticSimilarity;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate writeTransaction;
    private final String demoMchNo;
    private final double threshold;
    private final boolean semanticAutoMergeEnabled;
    private final String calibrationId;

    public PayLensIncidentBatchService(JdbcTemplate jdbc, ObjectMapper mapper,
                                       PayLensIncidentObservationReader observationReader,
                                       PayLensIncidentSimilarityProvider semanticSimilarity,
                                       @Qualifier("payLensIncidentScanExecutor") ThreadPoolTaskExecutor executor,
                                       PlatformTransactionManager transactionManager,
                                       @Value("${paylens.demo.mch-no:}") String demoMchNo,
                                       @Value("${paylens.incidents.grouping-threshold:0.90}") double threshold,
                                       @Value("${paylens.incidents.semantic-auto-merge-enabled:false}") boolean semanticAutoMergeEnabled,
                                       @Value("${paylens.incidents.calibration-id:}") String calibrationId) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.observationReader = observationReader;
        this.semanticSimilarity = semanticSimilarity;
        this.executor = executor;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.demoMchNo = demoMchNo;
        this.threshold = threshold;
        this.semanticAutoMergeEnabled = semanticAutoMergeEnabled;
        this.calibrationId = calibrationId;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void resumeQueuedBatches() {
        if (demoMchNo == null || demoMchNo.isBlank()) return;
        jdbc.query("SELECT id FROM paylens_incident_batch WHERE mch_no=? AND status='QUEUED' ORDER BY created_at,id",
                (rs, row) -> rs.getString(1), demoMchNo).forEach(this::dispatch);
    }

    public BatchView submit(Instant from, Instant to, String idempotencyKey) {
        requireConfiguredMerchant();
        Instant now = Instant.now();
        Instant effectiveTo = to == null ? now : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(Duration.ofHours(1)) : from;
        if (!effectiveFrom.isBefore(effectiveTo)) throw new IllegalArgumentException("扫描开始时间必须早于结束时间");
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("单次扫描时间跨度最多 24 小时");
        }
        if (effectiveTo.isAfter(now.plusSeconds(60))) throw new IllegalArgumentException("扫描结束时间不能是未来时间");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("幂等键必须为 1 到 128 个字符");
        }
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO paylens_incident_batch(id,mch_no,idempotency_key,scan_from,scan_to,status,execution_mode)
                VALUES (?,?,?,?,?,'QUEUED','PENDING')
                ON CONFLICT (mch_no,idempotency_key) DO NOTHING
                """, id, demoMchNo, idempotencyKey.trim(), offset(effectiveFrom), offset(effectiveTo));
        BatchView batch = findBatch(inserted == 1 ? id : null, idempotencyKey.trim());
        if (inserted == 1) dispatch(batch.id());
        return batch;
    }

    public BatchView get(String batchId) {
        requireConfiguredMerchant();
        return jdbc.query("SELECT * FROM paylens_incident_batch WHERE id=? AND mch_no=?",
                (rs, row) -> mapBatch(rs), batchId, demoMchNo).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("扫描批次不存在或无权访问"));
    }

    public IncidentPage incidents(String batchId, int page, int pageSize) {
        BatchView batch = get(batchId);
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new IllegalArgumentException("分页参数超出范围");
        long total = jdbc.queryForObject("SELECT count(*) FROM paylens_incident WHERE batch_id=? AND mch_no=?",
                Long.class, batch.id(), demoMchNo);
        List<IncidentSummary> rows = jdbc.query("""
                SELECT id,batch_id,version,review_status,execution_mode,algorithm_version,statistics_json,created_at
                  FROM paylens_incident WHERE batch_id=? AND mch_no=? ORDER BY group_no
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new IncidentSummary(rs.getString("id"), rs.getString("batch_id"),
                rs.getInt("version"), rs.getString("review_status"), rs.getString("execution_mode"),
                rs.getString("algorithm_version"), readMap(rs.getString("statistics_json")),
                rs.getObject("created_at", OffsetDateTime.class)), batch.id(), demoMchNo,
                pageSize, Math.multiplyExact(page - 1, pageSize));
        return new IncidentPage(batch.id(), batch.status(), page, pageSize, total, rows);
    }

    public IncidentDetail detail(String incidentId) {
        requireConfiguredMerchant();
        IncidentRecord incident = jdbc.query("""
                SELECT id,batch_id,mch_no,version,review_status,execution_mode,algorithm_version,
                       grouping_evidence,statistics_json,created_at,updated_at
                  FROM paylens_incident WHERE id=? AND mch_no=?
                """, (rs, row) -> new IncidentRecord(rs.getString("id"), rs.getString("batch_id"),
                rs.getString("mch_no"), rs.getInt("version"), rs.getString("review_status"),
                rs.getString("execution_mode"), rs.getString("algorithm_version"),
                readMap(rs.getString("grouping_evidence")), readMap(rs.getString("statistics_json")),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)),
                incidentId, demoMchNo).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("事件不存在或无权访问"));
        List<Observation> members = jdbc.query("""
                SELECT observation_json FROM paylens_incident_member
                 WHERE incident_id=? AND batch_id=? ORDER BY observation_id
                """, (rs, row) -> readValue(rs.getString(1), Observation.class), incident.id(), incident.batchId());
        return new IncidentDetail(incident.id(), incident.batchId(), incident.version(), incident.reviewStatus(),
                incident.executionMode(), incident.algorithmVersion(), incident.groupingEvidence(),
                incident.statistics(), members, incident.createdAt(), incident.updatedAt());
    }

    private void dispatch(String id) {
        try {
            executor.execute(() -> process(id));
        } catch (TaskRejectedException exception) {
            jdbc.update("UPDATE paylens_incident_batch SET status='FAILED',failure_code='SCAN_QUEUE_FULL',updated_at=now() WHERE id=? AND status='QUEUED'", id);
        }
    }

    private void process(String id) {
        int claimed = jdbc.update("UPDATE paylens_incident_batch SET status='RUNNING',updated_at=now() WHERE id=? AND mch_no=? AND status='QUEUED'",
                id, demoMchNo);
        if (claimed != 1) return;
        try {
            BatchRange range = jdbc.query("SELECT scan_from,scan_to FROM paylens_incident_batch WHERE id=? AND mch_no=?",
                    (rs, row) -> new BatchRange(rs.getObject(1, OffsetDateTime.class).toInstant(),
                            rs.getObject(2, OffsetDateTime.class).toInstant()), id, demoMchNo).getFirst();
            List<Observation> observations = observationReader.read(demoMchNo, range.from(), range.to());
            if (observations.size() > MAX_OBSERVATIONS) {
                failBatch(id, "SCAN_LIMIT_EXCEEDED");
                return;
            }
            String snapshot = writeJson(observations);
            boolean semanticMode = semanticAutoMergeEnabled && calibrationId != null && !calibrationId.isBlank()
                    && semanticSimilarity.semanticReady();
            GroupingResult groups;
            if (semanticMode) {
                try {
                    semanticSimilarity.prepare(observations);
                    groups = new CompleteLinkGrouper(semanticSimilarity).group(observations,
                            new GroupingPolicy(threshold, Duration.ofMinutes(10)));
                } catch (RuntimeException inferenceFailure) {
                    semanticMode = false;
                    log.warn("本次批次本地推理失败，改用精确描述规则归并（{}）", safeFailureCode(inferenceFailure));
                    groups = ruleGroups(observations);
                } finally {
                    semanticSimilarity.clearPrepared();
                }
            } else {
                groups = ruleGroups(observations);
            }
            persistResult(id, snapshot, observations, groups, semanticMode);
        } catch (Exception exception) {
            log.error("PayLens 异常扫描批次失败（{}）", safeFailureCode(exception));
            failBatch(id, safeFailureCode(exception));
        }
    }

    private GroupingResult ruleGroups(List<Observation> observations) {
        SimilarityProvider exactDescription = (left, right) -> normalize(left.normalizedDescription())
                .equals(normalize(right.normalizedDescription())) ? 1.0 : 0.0;
        return new CompleteLinkGrouper(exactDescription).group(observations,
                new GroupingPolicy(threshold, Duration.ofMinutes(10)));
    }

    private void persistResult(String batchId, String snapshot, List<Observation> observations,
                               GroupingResult grouping, boolean semanticMode) {
        Map<String, TradeAmounts> amountsByTrade = readTradeAmounts(observations);
        String mode = semanticMode ? "SEMANTIC" : "RULE_ONLY";
        writeTransaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE paylens_incident_batch SET snapshot_json=?::jsonb,input_count=?,execution_mode=?,updated_at=now() WHERE id=? AND mch_no=?",
                    snapshot, observations.size(), mode, batchId, demoMchNo);
            int groupNumber = 0;
            for (EventGroup group : grouping.groups()) {
                groupNumber++;
                String incidentId = UUID.randomUUID().toString();
                String stats = writeJson(statistics(group, amountsByTrade));
                String evidence = writeJson(groupingEvidence(group, semanticMode));
                jdbc.update("""
                        INSERT INTO paylens_incident(id,batch_id,mch_no,group_no,version,review_status,
                          execution_mode,algorithm_version,grouping_evidence,statistics_json)
                        VALUES (?,?,?, ?,1,'OPEN',?,?,?::jsonb,?::jsonb)
                        """, incidentId, batchId, demoMchNo, groupNumber, mode, group.algorithmVersion(), evidence, stats);
                for (Observation observation : group.observations()) {
                    jdbc.update("""
                            INSERT INTO paylens_incident_member(batch_id,incident_id,observation_id,observation_json)
                            VALUES (?,?,?,?::jsonb)
                            """, batchId, incidentId, observation.id(), writeJson(observation));
                }
            }
            jdbc.update("UPDATE paylens_incident_batch SET status='SUCCEEDED',incident_count=?,updated_at=now() WHERE id=? AND mch_no=?",
                    grouping.groups().size(), batchId, demoMchNo);
        });
    }

    private Map<String, Object> statistics(EventGroup group, Map<String, TradeAmounts> tradeAmounts) {
        List<Observation> members = group.observations();
        Instant first = members.stream().map(Observation::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant last = members.stream().map(Observation::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        Map<String, Long> typeCounts = new TreeMap<>();
        members.forEach(item -> typeCounts.merge(item.abnormalType() == null ? "UNKNOWN" : item.abnormalType(), 1L, Long::sum));
        Map<String, BigInteger> currencySums = new TreeMap<>();
        members.stream().map(Observation::tradeNo).distinct().map(tradeAmounts::get).filter(java.util.Objects::nonNull)
                .filter(value -> value.amount() != null && value.currency() != null)
                .forEach(value -> currencySums.merge(value.currency(), BigInteger.valueOf(value.amount()), BigInteger::add));
        return Map.of("observationCount", members.size(), "distinctTradeCount", group.distinctTradeCount(),
                "firstObservedAt", first, "lastObservedAt", last, "typeDistribution", typeCounts,
                "ordersAmountByCurrency", currencySums);
    }

    private Map<String, Object> groupingEvidence(EventGroup group, boolean semanticMode) {
        Observation first = group.observations().getFirst();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("algorithmVersion", group.algorithmVersion());
        evidence.put("executionMode", semanticMode ? "SEMANTIC" : "RULE_ONLY");
        evidence.put("groupingThreshold", semanticMode ? threshold : null);
        if (semanticMode) {
            evidence.put("calibrationId", calibrationId);
            evidence.put("embeddingModel", semanticSimilarity.modelMetadata());
        }
        evidence.put("minimumPairwiseSimilarity", group.minimumPairwiseSimilarity().isPresent()
                ? group.minimumPairwiseSimilarity().getAsDouble() : null);
        evidence.put("hardBoundary", Map.of("merchantKnown", first.mchNo() != null,
                "businessType", value(first.businessType()), "phase", value(first.phase()),
                "channel", value(first.channel()), "abnormalType", value(first.abnormalType())));
        return evidence;
    }

    private Map<String, TradeAmounts> readTradeAmounts(List<Observation> observations) {
        List<String> tradeNos = observations.stream().map(Observation::tradeNo).distinct().toList();
        if (tradeNos.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(tradeNos.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(demoMchNo);
        args.addAll(tradeNos);
        Map<String, TradeAmounts> result = new LinkedHashMap<>();
        jdbc.query("SELECT trade_no,amount,currency FROM pay_trade WHERE mch_no=? AND trade_no IN (" + placeholders + ") AND deleted=false",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.putIfAbsent(rs.getString("trade_no"), new TradeAmounts(
                        rs.getObject("amount", Long.class), rs.getString("currency"))), args.toArray());
        return result;
    }

    private BatchView findBatch(String id, String idempotencyKey) {
        return jdbc.query(id == null
                        ? "SELECT * FROM paylens_incident_batch WHERE mch_no=? AND idempotency_key=?"
                        : "SELECT * FROM paylens_incident_batch WHERE id=? AND mch_no=?",
                (rs, row) -> mapBatch(rs), id == null ? demoMchNo : id, id == null ? idempotencyKey : demoMchNo)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("扫描批次写入后无法读取"));
    }

    private BatchView mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchView(rs.getString("id"), rs.getString("status"), rs.getString("execution_mode"),
                rs.getInt("input_count"), rs.getInt("incident_count"), rs.getString("failure_code"),
                rs.getObject("scan_from", OffsetDateTime.class), rs.getObject("scan_to", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try { return mapper.readValue(json, Map.class); }
        catch (JacksonException exception) { throw new IllegalStateException("事件 JSON 无法读取", exception); }
    }

    private <T> T readValue(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JacksonException exception) { throw new IllegalStateException("事件成员快照无法读取", exception); }
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("事件数据无法序列化", exception); }
    }

    private void failBatch(String id, String failureCode) {
        jdbc.update("UPDATE paylens_incident_batch SET status='FAILED',failure_code=?,updated_at=now() WHERE id=? AND mch_no=? AND status IN ('QUEUED','RUNNING')",
                failureCode, id, demoMchNo);
    }

    private String safeFailureCode(Exception exception) {
        String message = exception.getMessage();
        if (message != null && message.matches("[A-Z0-9_]{1,128}")) return message;
        if (exception instanceof TaskRejectedException) return "SCAN_QUEUE_FULL";
        if (exception instanceof IllegalArgumentException) return "INVALID_SCAN_DATA";
        return "INCIDENT_BATCH_FAILED";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String value(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }
    private OffsetDateTime offset(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private void requireConfiguredMerchant() {
        if (demoMchNo == null || demoMchNo.isBlank()) throw new IllegalStateException("未配置专用演示商户");
    }

    public record BatchView(String id, String status, String executionMode, int inputCount, int incidentCount,
                            String failureCode, OffsetDateTime scanFrom, OffsetDateTime scanTo,
                            OffsetDateTime createdAt) {}
    public record IncidentPage(String batchId, String batchStatus, int page, int pageSize, long total,
                               List<IncidentSummary> items) {
        public IncidentPage { items = List.copyOf(items); }
    }
    public record IncidentSummary(String id, String batchId, int version, String reviewStatus, String executionMode,
                                  String algorithmVersion, Map<String, Object> statistics, OffsetDateTime createdAt) {}
    public record IncidentDetail(String id, String batchId, int version, String reviewStatus, String executionMode,
                                 String algorithmVersion, Map<String, Object> groupingEvidence, Map<String, Object> statistics,
                                 List<Observation> members, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public IncidentDetail { members = List.copyOf(members); }
    }
    private record BatchRange(Instant from, Instant to) {}
    private record TradeAmounts(Long amount, String currency) {}
    private record IncidentRecord(String id, String batchId, String mchNo, int version, String reviewStatus,
                                  String executionMode, String algorithmVersion,
                                  Map<String, Object> groupingEvidence, Map<String, Object> statistics,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
