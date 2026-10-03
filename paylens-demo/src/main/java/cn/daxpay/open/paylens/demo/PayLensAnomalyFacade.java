package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.analysis.DiagnosticEvidence;
import cn.daxpay.open.paylens.analysis.DiagnosticInput;
import cn.daxpay.open.paylens.analysis.SensitiveTextSanitizer;
import cn.daxpay.open.payment.trade.abnormal.dao.AbnormalOrderManager;
import cn.daxpay.open.payment.trade.abnormal.entity.AbnormalOrder;
import cn.daxpay.open.payment.trade.order.dao.PayTradeManager;
import cn.daxpay.open.payment.trade.order.entity.PayTrade;
import cn.daxpay.open.payment.trade.enums.PayFundStatusEnum;
import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.common.context.PaymentContext;
import cn.daxpay.open.payment.trade.record.dao.PayCallbackRecordManager;
import cn.daxpay.open.payment.trade.record.dao.PaySyncRecordManager;
import cn.daxpay.open.payment.trade.record.entity.PayCallbackRecord;
import cn.daxpay.open.payment.trade.record.entity.PaySyncRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PayLensAnomalyFacade {
    private final AbnormalOrderManager abnormalOrderManager;
    private final PayTradeManager payTradeManager;
    private final PayCallbackRecordManager callbackRecordManager;
    private final PaySyncRecordManager syncRecordManager;
    private final JdbcTemplate jdbc;
    private final PaymentContext paymentContext;
    private final MerchantContextLoader merchantContextLoader;
    @Value("${paylens.demo.mch-no:}")
    private String demoMchNo;

    public AnomalyDetail detail(Long abnormalId) {
        requireDemoMerchant();
        return inDemoMerchantContext(() -> {
            AbnormalOrder order = abnormalOrderManager.findById(abnormalId)
                    .orElseThrow(() -> new IllegalArgumentException("异常订单不存在或无权访问"));
            if (!demoMchNo.equals(order.getMchNo())) {
                throw new IllegalArgumentException("异常订单不存在或无权访问");
            }
            PayTrade trade = payTradeManager.findByTradeNoNotTenant(order.getTradeNo())
                    .filter(item -> demoMchNo.equals(item.getMchNo()))
                    .orElseThrow(() -> new IllegalArgumentException("异常订单不存在或无权访问"));
            return buildDetail(order, trade);
        });
    }

    public List<DemoTradeRow> demoTrades() {
        requireDemoMerchant();
        List<DemoEventRow> events = jdbc.query("""
                SELECT trade_no,scenario,remote_status,occurred_at
                FROM paylens_demo_event WHERE mch_no=? ORDER BY occurred_at DESC,id DESC LIMIT 150
                """, (rs, row) -> new DemoEventRow(rs.getString("trade_no"), rs.getString("scenario"),
                rs.getString("remote_status"), rs.getObject("occurred_at", OffsetDateTime.class)), demoMchNo);
        LinkedHashMap<String, DemoEventRow> latest = new LinkedHashMap<>();
        events.forEach(event -> latest.putIfAbsent(event.tradeNo(), event));
        return latest.values().stream().limit(30).map(event -> payTradeManager.findByTradeNoNotTenant(event.tradeNo())
                .filter(trade -> demoMchNo.equals(trade.getMchNo()))
                .map(trade -> new DemoTradeRow(event.tradeNo(), event.scenario(), event.remoteStatus(),
                        trade.getStatus(), trade.getAmount(), trade.getCurrency(), event.occurredAt()))
                .orElse(null)).filter(Objects::nonNull).toList();
    }

    public AnomalyDetail detailForDemoTrade(String tradeNo) {
        requireDemoMerchant();
        return inDemoMerchantContext(() -> {
            PayTrade trade = payTradeManager.findByTradeNoNotTenant(tradeNo)
                    .filter(item -> demoMchNo.equals(item.getMchNo()))
                    .orElseThrow(() -> new IllegalArgumentException("演示交易不存在或无权访问"));
            Integer count = jdbc.queryForObject("SELECT count(*) FROM paylens_demo_event WHERE mch_no=? AND trade_no=?",
                    Integer.class, demoMchNo, tradeNo);
            if (count == null || count == 0) throw new IllegalArgumentException("演示交易不存在或无权访问");
            AbnormalOrder order = abnormalOrderManager.lambdaQuery()
                    .eq(AbnormalOrder::getMchNo, demoMchNo)
                    .eq(AbnormalOrder::getTradeNo, tradeNo).last("limit 1").one();
            return buildDetail(order, trade);
        });
    }

    private <T> T inDemoMerchantContext(Supplier<T> action) {
        return paymentContext.runAs(() -> {
            merchantContextLoader.initMch(demoMchNo);
            if (!Objects.equals(demoMchNo, paymentContext.getMchNo())) {
                throw new IllegalArgumentException("当前客户端无权访问配置的演示商户");
            }
            return action.get();
        });
    }

    private AnomalyDetail buildDetail(AbnormalOrder order, PayTrade trade) {
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        List<TimelineEvent> timeline = new ArrayList<>();

        if (order != null) {
            evidence.add(new DiagnosticEvidence("E-ABNORMAL-" + order.getId(), "ABNORMAL_ORDER",
                    firstNonNull(order.getHandleTime(), order.getCreateTime()),
                    "已建立异常处理记录；异常类型=" + safe(order.getAbnormalType()) + "；发现时本地状态=" + safe(order.getTradeStatus()) +
                            "；通道状态=" + safe(order.getChannelStatus()) + "；来源=" + safe(order.getSource())));
            timeline.add(new TimelineEvent("异常识别", order.getCreateTime(), order.getTradeStatus(),
                    "状态不一致已进入人工处理队列，当前处置状态=" + safe(order.getHandleStatus())));
            if (order.getHandleTime() != null) {
                timeline.add(new TimelineEvent("人工处置", order.getHandleTime(), order.getHandleAction(),
                        "操作人=" + safe(order.getHandler()) + "；备注=" + safe(order.getHandleRemark())));
            }
        }

        List<PayCallbackRecord> callbacks = callbackRecordManager.lambdaQuery()
                .eq(PayCallbackRecord::getTradeNo, trade.getTradeNo())
                .orderByDesc(PayCallbackRecord::getId).last("limit 20").list();
        for (PayCallbackRecord callback : callbacks) {
            String id = "E-CALLBACK-" + callback.getId();
            String summary = "回调处理状态=" + safe(callback.getStatus()) + "；回调类型=" + safe(callback.getCallbackType()) +
                    "；通道错误类别=" + providerErrorCategory(callback.getErrorMsg());
            evidence.add(new DiagnosticEvidence(id, "CALLBACK", callback.getCreateTime(), summary));
            timeline.add(new TimelineEvent("通道回调", callback.getCreateTime(), callback.getStatus(),
                    "回调类型=" + safe(callback.getCallbackType()) + "；通道错误类别=" + providerErrorCategory(callback.getErrorMsg())));
        }

        List<PaySyncRecord> syncs = syncRecordManager.lambdaQuery()
                .eq(PaySyncRecord::getTradeNo, trade.getTradeNo())
                .orderByDesc(PaySyncRecord::getId).last("limit 20").list();
        for (PaySyncRecord sync : syncs) {
            String id = "E-SYNC-" + sync.getId();
            String summary = "查单返回通道状态=" + safe(sync.getOutTradeStatus()) + "；是否调整=" + sync.isAdjust() +
                    "；错误码=" + safe(sync.getErrorCode()) + "；通道错误类别=" + providerErrorCategory(sync.getErrorMsg());
            evidence.add(new DiagnosticEvidence(id, "SYNC", sync.getCreateTime(), summary));
            timeline.add(new TimelineEvent("支付查单", sync.getCreateTime(), sync.getOutTradeStatus(),
                    "是否调整=" + sync.isAdjust() + "；错误码=" + safe(sync.getErrorCode()) + "；通道错误类别=" + providerErrorCategory(sync.getErrorMsg())));
        }

        jdbc.query("""
                SELECT id,event_type,remote_status,occurred_at,description
                FROM paylens_demo_event WHERE mch_no=? AND trade_no=? ORDER BY occurred_at,id LIMIT 50
                """, rs -> {
            String id = "E-DEMO-" + rs.getLong("id");
            OffsetDateTime time = rs.getObject("occurred_at", OffsetDateTime.class);
            String status = rs.getString("remote_status");
            String summary = safe(rs.getString("description"));
            evidence.add(new DiagnosticEvidence(id, "SIMULATED_EVENT", time, summary + "；模拟通道状态=" + safe(status)));
            timeline.add(new TimelineEvent("模拟事件 · " + safe(rs.getString("event_type")), time, status, summary));
                }, trade.getMchNo(), trade.getTradeNo());

        evidence.add(new DiagnosticEvidence("E-TRADE-" + trade.getId(), "TRADE", trade.getLastModifiedTime(),
                "平台当前资金状态=" + safe(trade.getStatus()) + "；交易金额最小单位=" + trade.getAmount() +
                        "；币种=" + safe(trade.getCurrency()) + "；有无通道订单号=" + (trade.getOutOrderNo() != null)));
        timeline.add(new TimelineEvent("平台交易", trade.getLastModifiedTime(), trade.getStatus(),
                "当前资金状态；金额以最小货币单位存储，币种=" + safe(trade.getCurrency())));
        timeline.sort(Comparator.comparing(TimelineEvent::occurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        String channelStatus = order == null ? latestDemoStatus(trade.getMchNo(), trade.getTradeNo()) : order.getChannelStatus();
        return new AnomalyDetail(order == null ? null : order.getId(), trade.getTradeNo(),
                order == null ? null : order.getBizOrderNo(), trade.getTitle(), trade.getAmount(), trade.getCurrency(),
                order == null ? "NONE" : order.getAbnormalType(),
                order == null ? trade.getStatus() : order.getTradeStatus(), trade.getStatus(), channelStatus,
                order == null ? "NOT_ABNORMAL" : order.getHandleStatus(), trade.getMchNo(), timeline, evidence);
    }

    private String latestDemoStatus(String mchNo, String tradeNo) {
        List<String> statuses = jdbc.query("SELECT remote_status FROM paylens_demo_event WHERE mch_no=? AND trade_no=? ORDER BY occurred_at DESC,id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), mchNo, tradeNo);
        return statuses.isEmpty() ? "UNKNOWN" : statuses.getFirst();
    }

    private void requireDemoMerchant() {
        if (demoMchNo == null || demoMchNo.isBlank()) throw new IllegalStateException("未配置 PayLens 演示商户");
    }

    public DiagnosticInput toInput(AnomalyDetail detail) {
        return new DiagnosticInput(detail.tradeNo(), detail.localStatus(), detail.channelStatus(),
                detail.abnormalType(), detail.evidence());
    }

    private OffsetDateTime firstNonNull(OffsetDateTime first, OffsetDateTime second) {
        return Objects.requireNonNullElse(first, second);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "未知";
        String text = SensitiveTextSanitizer.sanitize(value);
        return text.substring(0, Math.min(text.length(), 300));
    }

    private String providerErrorCategory(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return "无";
        String message = rawMessage.toLowerCase(java.util.Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out") || message.contains("超时")) return "TIMEOUT";
        if (message.contains("signature") || message.contains("unauthorized") || message.contains("credential") ||
                message.contains("签名") || message.contains("认证") || message.contains("鉴权")) return "AUTHENTICATION_OR_SIGNATURE_FAILURE";
        if (message.contains("reject") || message.contains("拒绝") || message.contains("不支持")) return "CHANNEL_REJECTED";
        if (message.contains("network") || message.contains("connect") || message.contains("socket") ||
                message.contains("dns") || message.contains("网络") || message.contains("连接")) return "NETWORK_FAILURE";
        if (message.contains("duplicate") || message.contains("重复") || message.contains("幂等")) return "DUPLICATE_REQUEST";
        return "REDACTED_PROVIDER_ERROR";
    }

    public record TimelineEvent(String type, OffsetDateTime occurredAt, String status, String summary) {}
    private record DemoEventRow(String tradeNo, String scenario, String remoteStatus, OffsetDateTime occurredAt) {}
    public record DemoTradeRow(String tradeNo, String scenario, String channelStatus, String localStatus,
                               Long amount, String currency, OffsetDateTime occurredAt) {}
    public record AnomalyDetail(Long abnormalId, String tradeNo, String bizOrderNo, String title, Long amount,
                                String currency, String abnormalType, String abnormalStatus, String localStatus, String channelStatus,
                                String handleStatus, String mchNo, List<TimelineEvent> timeline,
                                List<DiagnosticEvidence> evidence) {
        public AnomalyDetail { timeline = List.copyOf(timeline); evidence = List.copyOf(evidence); }
    }
}
