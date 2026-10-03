package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.payment.strategy.pay.AbsPayCloseStrategy;
import cn.daxpay.open.payment.strategy.pay.PayStrategyContext;
import cn.daxpay.open.platform.core.enums.pay.channel.ProductEnum;
import cn.daxpay.open.platform.core.enums.pay.pay.CloseTypeEnum;
import cn.daxpay.open.platform.core.exception.ChannelResultUnknownException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Closes only a still-processing synthetic channel order. */
@Component
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
public class PayLensSimulationCloseStrategy extends AbsPayCloseStrategy {
    private final JdbcTemplate jdbc;

    public PayLensSimulationCloseStrategy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProductEnum getProduct() {
        return ProductEnum.PAYLENS_SIM;
    }

    @Override
    public CloseTypeEnum doClose(PayStrategyContext context, boolean useCancel) {
        var trade = context.getTrade();
        int updated = jdbc.update("""
                UPDATE paylens_demo_channel_state SET remote_status='CLOSE',finish_time=NULL,updated_at=now()
                WHERE mch_no=? AND trade_no=? AND remote_status='PROCESSING'
                """, trade.getMchNo(), trade.getTradeNo());
        if (updated != 1) {
            String status = jdbc.query("SELECT remote_status FROM paylens_demo_channel_state WHERE mch_no=? AND trade_no=?",
                    rs -> rs.next() ? rs.getString(1) : null, trade.getMchNo(), trade.getTradeNo());
            if ("UNKNOWN".equals(status)) throw new ChannelResultUnknownException("模拟通道状态未知，未执行远程关单");
            if (!"CLOSE".equals(status)) throw new ChannelResultUnknownException("模拟通道状态已变化，拒绝覆盖通道状态");
        }
        return useCancel ? CloseTypeEnum.CANCEL : CloseTypeEnum.TIMEOUT;
    }
}
