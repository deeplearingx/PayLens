package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.incidents.domain.Observation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Audited, optimistic-versioned manual split/merge operations for one demo merchant. */
@Service
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
public class PayLensIncidentAdjustmentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final String demoMchNo;

    public PayLensIncidentAdjustmentService(JdbcTemplate jdbc, ObjectMapper mapper,
                                            PlatformTransactionManager transactionManager,
                                            @Value("${paylens.demo.mch-no:}") String demoMchNo) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.demoMchNo = demoMchNo;
    }

    public SplitResult split(String incidentId, int expectedVersion, List<List<String>> requestedGroups,
                             String remark, String actor) {
        validateRemark(remark, actor);
        if (requestedGroups == null || requestedGroups.size() < 2 || requestedGroups.size() > 20) {
            throw new IllegalArgumentException("拆分必须提供 2 到 20 个成员分组");
        }
        List<List<String>> groups = requestedGroups.stream().map(group -> group == null ? List.<String>of()
                : group.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList()).toList();
        if (groups.stream().anyMatch(List::isEmpty)) throw new IllegalArgumentException("拆分后的每组至少包含一条观察记录");
        List<String> createdIds = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int[] newVersion = new int[1];
        transaction.executeWithoutResult(status -> {
            LockedIncident incident = lockIncidents(List.of(incidentId)).getFirst();
            verifyVersion(incident, expectedVersion);
            if ("ARCHIVED".equals(incident.reviewStatus())) throw new IncidentVersionConflictException();
            lockBatch(incident.batchId());
            List<Observation> existingMembers = members(incident.id());
            Set<String> expectedIds = existingMembers.stream().map(Observation::id).collect(java.util.stream.Collectors.toSet());
            Set<String> submittedIds = new HashSet<>();
            for (List<String> group : groups) {
                for (String memberId : group) {
                    if (!submittedIds.add(memberId)) throw new IllegalArgumentException("同一观察记录不能被分入多个组");
                }
            }
            if (!submittedIds.equals(expectedIds)) throw new IllegalArgumentException("拆分分组必须完整且仅覆盖当前全部成员");
            Map<String, Observation> byId = new LinkedHashMap<>();
            existingMembers.forEach(member -> byId.put(member.id(), member));
            List<List<Observation>> partitions = groups.stream().map(group -> group.stream().map(byId::get).toList()).toList();
            recordRevision(incident, "SPLIT", actor, remark);
            int groupNo = jdbc.queryForObject("SELECT COALESCE(MAX(group_no),0) FROM paylens_incident WHERE batch_id=?",
                    Integer.class, incident.batchId());
            Map<String, Object> existingEvidence = readMap(incident.groupingEvidence());
            List<Observation> primary = partitions.getFirst();
            updateIncident(incident, incident.version() + 1, "OPEN", "MANUAL", "manual-split-v1",
                    manualEvidence(existingEvidence, "SPLIT", actor, remark), primary);
            newVersion[0] = incident.version() + 1;
            createdIds.add(incident.id());
            counts.add(primary.size());
            saveCurrentRevision(incident.id(), newVersion[0], "SPLIT", actor, remark);
            for (int index = 1; index < partitions.size(); index++) {
                String childId = UUID.randomUUID().toString();
                groupNo++;
                List<Observation> partition = partitions.get(index);
                String evidence = writeJson(manualEvidence(existingEvidence, "SPLIT", actor, remark));
                String statistics = writeJson(statistics(partition));
                jdbc.update("""
                        INSERT INTO paylens_incident(id,batch_id,mch_no,group_no,version,review_status,
                          execution_mode,algorithm_version,grouping_evidence,statistics_json)
                        VALUES (?,?,?,?,1,'OPEN','MANUAL','manual-split-v1',?::jsonb,?::jsonb)
                        """, childId, incident.batchId(), demoMchNo, groupNo, evidence, statistics);
                for (Observation member : partition) {
                    int changed = jdbc.update("UPDATE paylens_incident_member SET incident_id=? WHERE batch_id=? AND incident_id=? AND observation_id=?",
                            childId, incident.batchId(), incident.id(), member.id());
                    if (changed != 1) throw new IncidentVersionConflictException();
                }
                createdIds.add(childId);
                counts.add(partition.size());
                saveCurrentRevision(childId, 1, "SPLIT", actor, remark);
            }
        });
        return new SplitResult(incidentId, newVersion[0], List.copyOf(createdIds), List.copyOf(counts));
    }

    public MergeResult merge(List<VersionRef> references, String remark, String actor) {
        validateRemark(remark, actor);
        if (references == null || references.size() < 2 || references.size() > 50) {
            throw new IllegalArgumentException("合并必须选择 2 到 50 个事件");
        }
        List<VersionRef> refs = references.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(VersionRef::incidentId)).toList();
        if (refs.stream().anyMatch(ref -> ref.incidentId() == null || ref.incidentId().isBlank() || ref.version() < 1)
                || refs.stream().map(VersionRef::incidentId).distinct().count() != refs.size()) {
            throw new IllegalArgumentException("事件版本列表无效或存在重复事件");
        }
        List<String> ids = refs.stream().map(VersionRef::incidentId).toList();
        String[] targetId = new String[1];
        int[] targetVersion = new int[1];
        List<String> archivedIds = new ArrayList<>();
        int[] memberCount = new int[1];
        transaction.executeWithoutResult(status -> {
            List<LockedIncident> incidents = lockIncidents(ids);
            if (incidents.size() != ids.size()) throw new IllegalArgumentException("事件不存在或无权访问");
            Map<String, Integer> versions = new LinkedHashMap<>();
            refs.forEach(ref -> versions.put(ref.incidentId(), ref.version()));
            incidents.forEach(incident -> {
                verifyVersion(incident, versions.get(incident.id()));
                if ("ARCHIVED".equals(incident.reviewStatus())) throw new IncidentVersionConflictException();
            });
            String batchId = incidents.getFirst().batchId();
            if (incidents.stream().anyMatch(incident -> !batchId.equals(incident.batchId()))) {
                throw new IllegalArgumentException("只能合并同一扫描批次内的事件");
            }
            lockBatch(batchId);
            List<Observation> allMembers = new ArrayList<>();
            for (LockedIncident incident : incidents) allMembers.addAll(members(incident.id()));
            ensureManualMergeBoundaries(allMembers);
            if (allMembers.isEmpty()) throw new IncidentVersionConflictException();
            // Use stable order so the target does not depend on request order.
            LockedIncident target = incidents.stream().min(Comparator.comparing(LockedIncident::createdAt)
                    .thenComparing(LockedIncident::id)).orElseThrow();
            incidents.forEach(incident -> recordRevision(incident, "MERGE", actor, remark));
            String idPlaceholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            List<Object> memberArgs = new ArrayList<>();
            memberArgs.add(target.id());
            memberArgs.add(batchId);
            memberArgs.addAll(ids);
            jdbc.update("UPDATE paylens_incident_member SET incident_id=? WHERE batch_id=? AND incident_id IN (" + idPlaceholders + ")",
                    memberArgs.toArray());
            Map<String, Object> evidence = manualEvidence(readMap(target.groupingEvidence()), "MERGE", actor, remark);
            int nextVersion = target.version() + 1;
            updateIncident(target, nextVersion, "OPEN", "MANUAL", "manual-merge-v1", evidence, allMembers);
            targetId[0] = target.id();
            targetVersion[0] = nextVersion;
            memberCount[0] = allMembers.size();
            saveCurrentRevision(target.id(), nextVersion, "MERGE", actor, remark);
            for (LockedIncident incident : incidents) {
                if (incident.id().equals(target.id())) continue;
                int archivedVersion = incident.version() + 1;
                Map<String, Object> archivedEvidence = manualEvidence(readMap(incident.groupingEvidence()),
                        "MERGE_SOURCE", actor, remark);
                jdbc.update("UPDATE paylens_incident SET version=?,review_status='ARCHIVED',execution_mode='MANUAL',algorithm_version='manual-merge-v1',grouping_evidence=?::jsonb,updated_at=now() WHERE id=? AND mch_no=? AND version=?",
                        archivedVersion, writeJson(archivedEvidence), incident.id(), demoMchNo, incident.version());
                archivedIds.add(incident.id());
                saveCurrentRevision(incident.id(), archivedVersion, "MERGE_SOURCE", actor, remark);
            }
        });
        return new MergeResult(targetId[0], targetVersion[0], memberCount[0], List.copyOf(archivedIds));
    }

    public Progress refresh(String incidentId) {
        LockedIncident incident = lockIncidents(List.of(incidentId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("事件不存在或无权访问"));
        List<Observation> members = members(incident.id());
        List<String> tradeNos = members.stream().map(Observation::tradeNo).distinct().sorted().toList();
        Map<String, Long> statuses = new TreeMap<>();
        if (!tradeNos.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(tradeNos.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(demoMchNo);
            args.addAll(tradeNos);
            jdbc.query("SELECT trade_no,status FROM pay_trade WHERE mch_no=? AND trade_no IN (" + placeholders + ") AND deleted=false",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> statuses.merge(
                            rs.getString("status") == null ? "UNKNOWN" : rs.getString("status"), 1L, Long::sum), args.toArray());
        }
        return new Progress(incident.id(), incident.version(), tradeNos.size(), statuses, OffsetDateTime.now());
    }

    private List<LockedIncident> lockIncidents(List<String> ids) {
        if (demoMchNo == null || demoMchNo.isBlank()) throw new IllegalStateException("未配置专用演示商户");
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(demoMchNo);
        args.addAll(ids);
        return jdbc.query("""
                SELECT id,batch_id,mch_no,version,review_status,execution_mode,algorithm_version,
                       grouping_evidence::text AS grouping_evidence,created_at
                  FROM paylens_incident WHERE mch_no=? AND id IN (
                """ + placeholders + ") ORDER BY id FOR UPDATE", (rs, row) -> new LockedIncident(
                rs.getString("id"), rs.getString("batch_id"), rs.getString("mch_no"), rs.getInt("version"),
                rs.getString("review_status"), rs.getString("execution_mode"), rs.getString("algorithm_version"),
                rs.getString("grouping_evidence"), rs.getObject("created_at", OffsetDateTime.class)), args.toArray());
    }

    private void lockBatch(String batchId) {
        List<String> locked = jdbc.query("SELECT id FROM paylens_incident_batch WHERE id=? AND mch_no=? FOR UPDATE",
                (rs, row) -> rs.getString(1), batchId, demoMchNo);
        if (locked.isEmpty()) throw new IllegalArgumentException("扫描批次不存在或无权访问");
    }

    private void verifyVersion(LockedIncident incident, Integer expectedVersion) {
        if (expectedVersion == null || incident.version() != expectedVersion) throw new IncidentVersionConflictException();
    }

    private List<Observation> members(String incidentId) {
        return jdbc.query("SELECT observation_json::text FROM paylens_incident_member WHERE incident_id=? ORDER BY observation_id",
                (rs, row) -> readValue(rs.getString(1)), incidentId);
    }

    private void updateIncident(LockedIncident incident, int version, String reviewStatus, String executionMode,
                                String algorithmVersion, Map<String, Object> evidence, List<Observation> members) {
        Map<String, Object> stats = statistics(members);
        int changed = jdbc.update("""
                UPDATE paylens_incident SET version=?,review_status=?,execution_mode=?,algorithm_version=?,
                  grouping_evidence=?::jsonb,statistics_json=?::jsonb,updated_at=now()
                 WHERE id=? AND mch_no=? AND version=?
                """, version, reviewStatus, executionMode, algorithmVersion, writeJson(evidence), writeJson(stats),
                incident.id(), demoMchNo, incident.version());
        if (changed != 1) throw new IncidentVersionConflictException();
    }

    private Map<String, Object> statistics(List<Observation> members) {
        var sorted = members.stream().sorted(Comparator.comparing(Observation::occurredAt).thenComparing(Observation::id)).toList();
        Map<String, Long> types = new TreeMap<>();
        sorted.forEach(member -> types.merge(member.abnormalType() == null ? "UNKNOWN" : member.abnormalType(), 1L, Long::sum));
        Map<String, BigInteger> amounts = new TreeMap<>();
        List<String> tradeNos = sorted.stream().map(Observation::tradeNo).distinct().toList();
        if (!tradeNos.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(tradeNos.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(demoMchNo);
            args.addAll(tradeNos);
            jdbc.query("SELECT trade_no,amount,currency FROM pay_trade WHERE mch_no=? AND trade_no IN (" + placeholders + ") AND deleted=false",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        Long amount = rs.getObject("amount", Long.class);
                        String currency = rs.getString("currency");
                        if (amount != null && currency != null) amounts.merge(currency, BigInteger.valueOf(amount), BigInteger::add);
                    }, args.toArray());
        }
        if (sorted.isEmpty()) return Map.of("observationCount", 0, "distinctTradeCount", 0,
                "typeDistribution", types, "ordersAmountByCurrency", amounts);
        return Map.of("observationCount", sorted.size(), "distinctTradeCount", tradeNos.size(),
                "firstObservedAt", sorted.getFirst().occurredAt(), "lastObservedAt", sorted.getLast().occurredAt(),
                "typeDistribution", types, "ordersAmountByCurrency", amounts);
    }

    private void ensureManualMergeBoundaries(List<Observation> members) {
        if (members.stream().anyMatch(member -> !demoMchNo.equals(member.mchNo()))) {
            throw new IllegalArgumentException("不能跨商户合并支付异常事件");
        }
        Observation first = members.getFirst();
        for (Observation member : members) {
            if (!sameKnown(first.businessType(), member.businessType()) || !sameKnown(first.phase(), member.phase())
                    || !sameKnown(first.channel(), member.channel()) || !sameKnown(first.abnormalType(), member.abnormalType())) {
                throw new IllegalArgumentException("所选事件不满足相同商户、业务类型、阶段、通道及异常类型的硬边界");
            }
            if (known(first.errorCode()) && known(member.errorCode()) && !first.errorCode().equals(member.errorCode())) {
                throw new IllegalArgumentException("明确不同错误码的事件不能合并");
            }
        }
    }

    private void recordRevision(LockedIncident incident, String operation, String actor, String remark) {
        String snapshot = snapshot(incident.id());
        jdbc.update("""
                INSERT INTO paylens_incident_revision(incident_id,version,operation,actor,remark,snapshot_json)
                VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT (incident_id,version) DO NOTHING
                """, incident.id(), incident.version(), operation, actor, remark, snapshot);
    }

    private void saveCurrentRevision(String incidentId, int version, String operation, String actor, String remark) {
        jdbc.update("""
                INSERT INTO paylens_incident_revision(incident_id,version,operation,actor,remark,snapshot_json)
                VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT (incident_id,version) DO NOTHING
                """, incidentId, version, operation, actor, remark, snapshot(incidentId));
    }

    private String snapshot(String incidentId) {
        Map<String, Object> snapshot = jdbc.query("""
                SELECT id,batch_id,mch_no,group_no,version,review_status,execution_mode,algorithm_version,
                       grouping_evidence::text AS grouping_evidence,statistics_json::text AS statistics_json
                  FROM paylens_incident WHERE id=? AND mch_no=?
                """, (rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getString("id"));
            result.put("batchId", rs.getString("batch_id"));
            result.put("merchant", rs.getString("mch_no"));
            result.put("groupNo", rs.getInt("group_no"));
            result.put("version", rs.getInt("version"));
            result.put("reviewStatus", rs.getString("review_status"));
            result.put("executionMode", rs.getString("execution_mode"));
            result.put("algorithmVersion", rs.getString("algorithm_version"));
            result.put("groupingEvidence", readMap(rs.getString("grouping_evidence")));
            result.put("statistics", readMap(rs.getString("statistics_json")));
            return result;
        }, incidentId, demoMchNo).stream().findFirst().orElseThrow(() -> new IncidentVersionConflictException());
        snapshot.put("members", members(incidentId));
        return writeJson(snapshot);
    }

    private Map<String, Object> manualEvidence(Map<String, Object> existing, String operation, String actor, String remark) {
        Map<String, Object> result = new LinkedHashMap<>(existing);
        result.put("executionMode", "MANUAL");
        result.put("manualOperation", operation);
        result.put("actor", actor);
        result.put("remark", remark);
        return result;
    }

    private void validateRemark(String remark, String actor) {
        if (remark == null || remark.isBlank() || remark.length() > 500) throw new IllegalArgumentException("处置备注必须为 1 到 500 个字符");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("无法识别当前操作者");
    }

    private boolean sameKnown(String left, String right) { return known(left) && left.equals(right); }
    private boolean known(String value) { return value != null && !value.isBlank(); }
    private Map<String, Object> readMap(String json) {
        try { return mapper.readValue(json, Map.class); }
        catch (JacksonException exception) { throw new IllegalStateException("事件历史快照无法读取", exception); }
    }
    private Observation readValue(String json) {
        try { return mapper.readValue(json, Observation.class); }
        catch (JacksonException exception) { throw new IllegalStateException("事件成员快照无法读取", exception); }
    }
    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("事件调整数据无法序列化", exception); }
    }

    public record VersionRef(String incidentId, int version) {}
    public record SplitResult(String incidentId, int version, List<String> incidentIds, List<Integer> memberCounts) {}
    public record MergeResult(String incidentId, int version, int memberCount, List<String> archivedIncidentIds) {}
    public record Progress(String incidentId, int version, int distinctTradeCount, Map<String, Long> currentStatusDistribution,
                           OffsetDateTime refreshedAt) {}
    private record LockedIncident(String id, String batchId, String mchNo, int version, String reviewStatus,
                                  String executionMode, String algorithmVersion, String groupingEvidence,
                                  OffsetDateTime createdAt) {}
}
