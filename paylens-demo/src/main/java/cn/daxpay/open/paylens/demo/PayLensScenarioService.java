package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.common.context.PaymentContext;
import cn.daxpay.open.payment.trade.enums.PayFundStatusEnum;
import cn.daxpay.open.payment.trade.runtime.bo.CallbackData;
import cn.daxpay.open.payment.trade.runtime.service.callback.PayCallbackService;
import cn.daxpay.open.payment.trade.runtime.service.pay.common.PayUniHandleService;
import cn.daxpay.open.payment.trade.runtime.service.pay.normal.NormalPayAssistService;
import cn.daxpay.open.payment.trade.record.service.PayCallbackRecordService;
import cn.daxpay.open.payment.strategy.pay.PayStrategyContext;
import cn.daxpay.open.payment.unipay.param.trade.pay.NormalPayParam;
import cn.daxpay.open.platform.core.enums.pay.notice.CallbackStatusEnum;
import cn.daxpay.open.payment.trade.order.dao.PayTradeManager;
import cn.daxpay.open.payment.trade.order.entity.PayTrade;
import cn.daxpay.open.platform.core.enums.pay.channel.ProductEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PayLensScenarioService {
    private final PaymentContext paymentContext;
    private final MerchantContextLoader merchantContextLoader;
    private final NormalPayAssistService normalPayAssistService;
    private final PayLensSimulationPayStrategy simulationPayStrategy;
    private final PayTradeManager payTradeManager;
    private final PayUniHandleService payUniHandleService;
    private final PayCallbackService payCallbackService;
    private final PayCallbackRecordService payCallbackRecordService;
    private final JdbcTemplate jdbc;

    @Value("${paylens.demo.enabled:false}")
    private boolean enabled;
    @Value("${paylens.demo.mch-no:}")
    private String demoMchNo;
    @Value("${paylens.demo.app-id:}")
    private String demoAppId;
    @Value("${paylens.demo.duplicate-callback-count:2}")
    private int duplicateCallbackCount;
    @PostConstruct
    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS paylens_demo_event (
                  id bigserial PRIMARY KEY,
                  mch_no varchar(64) NOT NULL,
                  trade_no varchar(64) NOT NULL,
                  scenario varchar(32) NOT NULL,
                  event_type varchar(32) NOT NULL,
                  remote_status varchar(16) NOT NULL,
                  occurred_at timestamptz NOT NULL DEFAULT now(),
                  description varchar(500) NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_paylens_demo_event_mch_trade ON paylens_demo_event(mch_no,trade_no,occurred_at)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS paylens_demo_channel_state (
                  mch_no varchar(64) NOT NULL,
                  trade_no varchar(64) NOT NULL,
                  remote_status varchar(16) NOT NULL,
                  finish_time timestamptz,
                  updated_at timestamptz NOT NULL DEFAULT now(),
                  PRIMARY KEY(mch_no,trade_no)
                )
                """);
    }

    public ScenarioResult run(String scenario) {
        assertEnabled();
        String normalized = scenario == null ? "" : scenario.trim().toUpperCase();
        if (!List.of("NORMAL_SUCCESS", "CALLBACK_LOST", "LATE_SUCCESS", "DUPLICATE_CALLBACK", "QUERY_TIMEOUT").contains(normalized)) {
            throw new IllegalArgumentException("未知演示场景");
        }
        if ("DUPLICATE_CALLBACK".equals(normalized) && (duplicateCallbackCount < 2 || duplicateCallbackCount > 100)) {
            throw new IllegalStateException("paylens.demo.duplicate-callback-count 必须在 2 到 100 之间");
        }
        if (demoMchNo.isBlank() || demoAppId.isBlank()) {
            throw new IllegalStateException("需配置 paylens.demo.mch-no 和 paylens.demo.app-id（使用专用演示商户）");
        }
        return paymentContext.runAs(() -> {
            merchantContextLoader.initMch(demoMchNo);
            String effectiveMchNo = paymentContext.getMchNo();
            if (!demoMchNo.equals(effectiveMchNo)) {
                throw new IllegalStateException("当前客户端商户范围与配置的 PayLens 演示商户不一致");
            }
            var app = merchantContextLoader.resolveApp(effectiveMchNo, demoAppId);
            NormalPayParam param = new NormalPayParam();
            param.setMchNo(effectiveMchNo);
            param.setAppId(app.getAppId());
            param.setBizOrderNo("PL" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            param.setTitle("PayLens 支付异常演示");
            param.setDescription("合成交易数据，仅用于求职演示");
            param.setAmount(100L);
            param.setCurrency("cny");
            param.setProduct(ProductEnum.PAYLENS_SIM.getCode());
            param.setReqId(UUID.randomUUID().toString());
            param.setReqTime(OffsetDateTime.now(ZoneOffset.UTC));
            PayStrategyContext context = new PayStrategyContext().setPayParam(param);
            normalPayAssistService.createOrder(param, context);
            simulationPayStrategy.doBeforePay(context);
            var payResult = simulationPayStrategy.doPay(context);
            PayTrade trade = context.getTrade();
            payUniHandleService.payAfterHandel(trade, payResult);
            saveEvent(trade.getTradeNo(), normalized, "ORDER_CREATED", "UNKNOWN", "通过 DaxPay 普通支付建单服务创建模拟订单，状态为处理中");
            return switch (normalized) {
                case "NORMAL_SUCCESS" -> {
                    updateChannelState(trade.getTradeNo(), "SUCCESS");
                    sendSuccessCallback(trade.getTradeNo());
                    saveEvent(trade.getTradeNo(), normalized, "CALLBACK_ACCEPTED", "SUCCESS", "模拟正常成功回调");
                    yield new ScenarioResult(trade.getTradeNo(), normalized, PayFundStatusEnum.SUCCESS.getCode(), "订单已通过 DaxPay 回调服务处理成功");
                }
                case "CALLBACK_LOST" -> {
                    updateChannelState(trade.getTradeNo(), "SUCCESS");
                    saveEvent(trade.getTradeNo(), normalized, "CALLBACK_LOST", "SUCCESS", "模拟通道已支付但成功回调丢失；本地订单保持处理中，可点击回放回调");
                    yield new ScenarioResult(trade.getTradeNo(), normalized, PayFundStatusEnum.PROCESSING.getCode(), "通道成功状态已模拟，平台未收到回调");
                }
                case "LATE_SUCCESS" -> {
                    updateChannelState(trade.getTradeNo(), "CLOSE");
                    payUniHandleService.payTimeout(trade);
                    saveEvent(trade.getTradeNo(), normalized, "LOCAL_TIMEOUT", "CLOSE", "使用 DaxPay 超时关单业务服务关闭本地订单");
                    updateChannelState(trade.getTradeNo(), "SUCCESS");
                    sendSuccessCallback(trade.getTradeNo());
                    saveEvent(trade.getTradeNo(), normalized, "LATE_SUCCESS_CALLBACK", "SUCCESS", "模拟终态关单后到达的通道成功回调");
                    yield new ScenarioResult(trade.getTradeNo(), normalized, PayFundStatusEnum.CLOSE.getCode(), "应生成待人工核实的异常订单");
                }
                case "DUPLICATE_CALLBACK" -> {
                    updateChannelState(trade.getTradeNo(), "SUCCESS");
                    for (int i = 0; i < duplicateCallbackCount; i++) {
                        sendSuccessCallback(trade.getTradeNo());
                    }
                    saveEvent(trade.getTradeNo(), normalized, "DUPLICATE_CALLBACK", "SUCCESS",
                            "向 DaxPay 回调服务重复发送 " + duplicateCallbackCount + " 次相同成功回调");
                    yield new ScenarioResult(trade.getTradeNo(), normalized, PayFundStatusEnum.SUCCESS.getCode(),
                            "重复成功通知由 DaxPay 回调处理逻辑幂等忽略", duplicateCallbackCount);
                }
                default -> {
                    updateChannelState(trade.getTradeNo(), "UNKNOWN");
                    saveEvent(trade.getTradeNo(), normalized, "QUERY_TIMEOUT", "UNKNOWN", "模拟通道查单超时；未知结果不推断为成功或失败");
                    yield new ScenarioResult(trade.getTradeNo(), normalized, PayFundStatusEnum.PROCESSING.getCode(), "查单结果未知，订单维持处理中");
                }
            };
        });
    }

    public ScenarioResult replaySuccessCallback(String tradeNo) {
        assertEnabled();
        if (demoMchNo.isBlank()) {
            throw new IllegalStateException("未配置专用 PayLens 演示商户");
        }
        PayTrade trade = payTradeManager.findByTradeNoNotTenant(tradeNo)
                .orElseThrow(() -> new IllegalArgumentException("交易不存在"));
        if (!demoMchNo.equals(trade.getMchNo())) {
            throw new IllegalArgumentException("只能回放配置的 PayLens 演示商户交易");
        }
        return paymentContext.runAs(() -> {
            merchantContextLoader.initMch(demoMchNo);
            if (!demoMchNo.equals(paymentContext.getMchNo())) {
                throw new IllegalStateException("当前客户端商户范围与配置的 PayLens 演示商户不一致");
            }
            String scenario = jdbc.query("""
                            SELECT scenario FROM paylens_demo_event
                            WHERE mch_no=? AND trade_no=? AND scenario IN ('CALLBACK_LOST','LATE_SUCCESS')
                            ORDER BY occurred_at LIMIT 1
                            """,
                    rs -> rs.next() ? rs.getString(1) : null, trade.getMchNo(), tradeNo);
            if (scenario == null) throw new IllegalArgumentException("该交易不是可回放成功回调的演示场景");
            assertChannelSuccess(tradeNo);
            sendSuccessCallback(tradeNo);
            saveEvent(tradeNo, scenario, "CALLBACK_REPLAYED", "SUCCESS", "并发测试回放通道成功通知，交由 DaxPay 回调服务处理");
            return new ScenarioResult(tradeNo, scenario, PayFundStatusEnum.SUCCESS.getCode(), "回放已完成");
        });
    }

    public void assertChannelSuccess(String tradeNo) {
        String status = jdbc.query("SELECT remote_status FROM paylens_demo_channel_state WHERE mch_no=? AND trade_no=?",
                rs -> rs.next() ? rs.getString(1) : null, demoMchNo, tradeNo);
        if (!"SUCCESS".equals(status)) throw new IllegalStateException("模拟通道尚无明确成功证据，拒绝确认成功");
    }

    private void updateChannelState(String tradeNo, String status) {
        jdbc.update("""
                INSERT INTO paylens_demo_channel_state(mch_no,trade_no,remote_status,finish_time,updated_at)
                VALUES (?,?,?,CASE WHEN ?='SUCCESS' THEN now() ELSE NULL END,now())
                ON CONFLICT(mch_no,trade_no) DO UPDATE SET remote_status=EXCLUDED.remote_status,
                  finish_time=EXCLUDED.finish_time,updated_at=now()
                """, paymentContext.getMchNo(), tradeNo, status, status);
    }

    private void sendSuccessCallback(String tradeNo) {
        CallbackData callback = new CallbackData().setTradeNo(tradeNo)
                .setOutTradeNo("SIM-" + tradeNo)
                .setTradeStatus(CallbackStatusEnum.SUCCESS.getCode())
                .setFinishTime(OffsetDateTime.now(ZoneOffset.UTC))
                .setCallbackData(Map.of("simulation", true, "notice", "synthetic demo callback"));
        payCallbackService.payCallback(callback);
        payCallbackRecordService.savePay("PAYLENS-SIMULATED", callback);
    }

    private void saveEvent(String tradeNo, String scenario, String type, String status, String description) {
        jdbc.update("INSERT INTO paylens_demo_event(mch_no,trade_no,scenario,event_type,remote_status,description) VALUES (?,?,?,?,?,?)",
                paymentContext.getMchNo(), tradeNo, scenario, type, status, description);
    }

    private void assertEnabled() {
        if (!enabled) throw new IllegalStateException("PayLens 演示场景未启用");
        initialize();
    }

    public record ScenarioResult(String tradeNo, String scenario, String localStatus, String message, int notificationCount) {
        public ScenarioResult(String tradeNo, String scenario, String localStatus, String message) {
            this(tradeNo, scenario, localStatus, message, 0);
        }
    }
}
