package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.incidents.domain.Observation;
import cn.daxpay.open.paylens.analysis.SensitiveTextSanitizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reads a bounded, stable view of source records before any embedding or Chat work starts. */
@Repository
public class PayLensIncidentObservationReader {
    private static final int READ_LIMIT = 501;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshotTransaction;

    public PayLensIncidentObservationReader(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setReadOnly(true);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public List<Observation> read(String mchNo, Instant from, Instant to) {
        return snapshotTransaction.execute(status -> {
            List<Observation> observations = new ArrayList<>();
            observations.addAll(readAbnormalOrders(mchNo, from, to));
            observations.addAll(readSyncFailures(mchNo, from, to));
            observations.addAll(readCallbackFailures(mchNo, from, to));
            observations.sort(Comparator.comparing(Observation::occurredAt)
                    .thenComparing(Observation::id));
            return List.copyOf(observations);
        });
    }

    private List<Observation> readAbnormalOrders(String mchNo, Instant from, Instant to) {
        return jdbc.query("""
                SELECT id,mch_no,trade_no,trade_type,channel,abnormal_type,trade_status,channel_status,create_time
                  FROM pay_abnormal_order
                 WHERE mch_no=? AND create_time>=? AND create_time<? AND deleted=false
                 ORDER BY create_time,id LIMIT ?
                """, (rs, row) -> {
            String abnormal = rs.getString("abnormal_type");
            String local = rs.getString("trade_status");
            String remote = rs.getString("channel_status");
            String desc = "支付异常单记录：平台状态=" + safe(local) + "，通道状态=" + safe(remote);
            return new Observation("abnormal:" + rs.getLong("id"), rs.getString("trade_no"),
                    rs.getString("mch_no"), rs.getString("trade_type"), "PAYMENT_STATE",
                    rs.getString("channel"), abnormal, null, instant(rs.getObject("create_time", OffsetDateTime.class)), desc);
        }, mchNo, offset(from), offset(to), READ_LIMIT);
    }

    private List<Observation> readSyncFailures(String mchNo, Instant from, Instant to) {
        return jdbc.query("""
                SELECT id,mch_no,trade_no,trade_type,channel,out_trade_status,error_code,error_msg,create_time
                  FROM pay_sync_record
                 WHERE mch_no=? AND create_time>=? AND create_time<? AND deleted=false
                   AND (NULLIF(error_code,'') IS NOT NULL OR NULLIF(error_msg,'') IS NOT NULL
                        OR out_trade_status IS NULL OR lower(out_trade_status) IN ('unknown','not_known'))
                 ORDER BY create_time,id LIMIT ?
                """, (rs, row) -> {
            String code = rs.getString("error_code");
            String rawMessage = rs.getString("error_msg");
            String reason = errorCategory(code, rawMessage, rs.getString("out_trade_status"));
            return new Observation("sync:" + rs.getLong("id"), rs.getString("trade_no"),
                    rs.getString("mch_no"), rs.getString("trade_type"), "QUERY",
                    rs.getString("channel"), reason, blankToNull(code),
                    instant(rs.getObject("create_time", OffsetDateTime.class)),
                    "支付查单出现异常：类别=" + reason + "，返回状态=" + safe(rs.getString("out_trade_status"))
                            + "，错误描述=" + safeDescription(rawMessage));
        }, mchNo, offset(from), offset(to), READ_LIMIT);
    }

    private List<Observation> readCallbackFailures(String mchNo, Instant from, Instant to) {
        return jdbc.query("""
                SELECT c.id,c.mch_no,c.trade_no,c.product,c.status,c.error_msg,c.create_time,
                       t.trade_type,t.channel
                  FROM pay_callback_record c
                  LEFT JOIN pay_trade t ON t.mch_no=c.mch_no AND t.trade_no=c.trade_no AND t.deleted=false
                 WHERE c.mch_no=? AND c.create_time>=? AND c.create_time<? AND c.deleted=false
                   AND c.callback_type='pay' AND lower(c.status) IN ('fail','exception','not_found')
                 ORDER BY c.create_time,c.id LIMIT ?
                """, (rs, row) -> {
            String status = rs.getString("status");
            String category = errorCategory(null, rs.getString("error_msg"), status);
            return new Observation("callback:" + rs.getLong("id"), rs.getString("trade_no"),
                    rs.getString("mch_no"), rs.getString("trade_type"), "CALLBACK",
                    rs.getString("channel"), "CALLBACK_" + safe(status).toUpperCase(Locale.ROOT), category,
                    instant(rs.getObject("create_time", OffsetDateTime.class)),
                    "支付回调处理失败：状态=" + safe(status) + "，错误类别=" + category
                            + "，错误描述=" + safeDescription(rs.getString("error_msg")));
        }, mchNo, offset(from), offset(to), READ_LIMIT);
    }

    private String errorCategory(String code, String message, String status) {
        String normalized = ((message == null ? "" : message) + " " + (status == null ? "" : status))
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("timeout") || normalized.contains("timed out") || normalized.contains("超时")) {
            return "TIMEOUT";
        }
        if (code != null && !code.isBlank()) return "ERROR_CODE_" + safe(code).toUpperCase(Locale.ROOT);
        if (message != null && !message.isBlank()) return "QUERY_ERROR";
        return "UNKNOWN_RESULT";
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "未知";
        String sanitized = SensitiveTextSanitizer.sanitize(value);
        return sanitized.substring(0, Math.min(sanitized.length(), 160));
    }
    private String safeDescription(String value) {
        if (value == null || value.isBlank()) return "未提供";
        String sanitized = SensitiveTextSanitizer.sanitize(value).replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.substring(0, Math.min(sanitized.length(), 320));
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : safe(value); }
    private OffsetDateTime offset(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private Instant instant(OffsetDateTime value) {
        if (value == null) throw new IllegalStateException("来源记录没有可用的观察时间，拒绝为扫描结果补造时间");
        return value.toInstant();
    }
}
