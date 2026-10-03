package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.payment.strategy.pay.PayStrategyContext;
import cn.daxpay.open.payment.strategy.sync.AbsSyncPayOrderStrategy;
import cn.daxpay.open.payment.trade.enums.PayFundStatusEnum;
import cn.daxpay.open.payment.trade.runtime.bo.PaySyncResultBo;
import cn.daxpay.open.platform.core.enums.pay.channel.ProductEnum;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** Reads only the persisted synthetic state for the PayLens demo merchant. */
@Component
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
public class PayLensSimulationSyncStrategy extends AbsSyncPayOrderStrategy {
    private final JdbcTemplate jdbc;

    public PayLensSimulationSyncStrategy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProductEnum getProduct() {
        return ProductEnum.PAYLENS_SIM;
    }

    @Override
    public PaySyncResultBo doSync(PayStrategyContext context) {
        var trade = context.getTrade();
        List<RemoteState> states = jdbc.query("""
                SELECT remote_status,finish_time FROM paylens_demo_channel_state WHERE mch_no=? AND trade_no=?
                """, (rs, row) -> new RemoteState(rs.getString("remote_status"),
                rs.getObject("finish_time", OffsetDateTime.class)), trade.getMchNo(), trade.getTradeNo());
        if (states.isEmpty() || "UNKNOWN".equals(states.getFirst().status())) {
            return new PaySyncResultBo().setSyncSuccess(false).setSyncErrorCode("SIM_QUERY_UNKNOWN")
                    .setSyncErrorMsg("模拟通道查单状态未知；本地订单保持原状态");
        }
        RemoteState state = states.getFirst();
        PayFundStatusEnum status = switch (state.status()) {
            case "SUCCESS" -> PayFundStatusEnum.SUCCESS;
            case "CLOSE" -> PayFundStatusEnum.CLOSE;
            case "FAIL" -> PayFundStatusEnum.FAIL;
            default -> PayFundStatusEnum.PROCESSING;
        };
        return new PaySyncResultBo().setPayStatus(status)
                .setOutOrderNo("SIM-" + trade.getTradeNo())
                .setAmount(trade.getAmount())
                .setRealAmount(status == PayFundStatusEnum.SUCCESS ? trade.getAmount() : 0L)
                .setFinishTime(state.finishTime())
                .setSyncData("{\"channel\":\"paylens-simulation\",\"status\":\"" + state.status() + "\"}");
    }

    private record RemoteState(String status, OffsetDateTime finishTime) {}
}
