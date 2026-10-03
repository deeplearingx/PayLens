package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.payment.strategy.pay.AbsNormalPayStrategy;
import cn.daxpay.open.payment.strategy.pay.PayStrategyContext;
import cn.daxpay.open.payment.trade.runtime.bo.PayTradeResultBo;
import cn.daxpay.open.platform.core.enums.pay.channel.ProductEnum;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** Local-only synthetic channel. It never contacts a real payment provider. */
@Component
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
public class PayLensSimulationPayStrategy extends AbsNormalPayStrategy {
    private final JdbcTemplate jdbc;

    public PayLensSimulationPayStrategy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProductEnum getProduct() {
        return ProductEnum.PAYLENS_SIM;
    }

    @Override
    public PayTradeResultBo doPay(PayStrategyContext context) {
        var trade = context.getTrade();
        jdbc.update("""
                INSERT INTO paylens_demo_channel_state(mch_no,trade_no,remote_status,updated_at)
                VALUES (?,?,?,now())
                ON CONFLICT(mch_no,trade_no) DO UPDATE SET remote_status=EXCLUDED.remote_status,
                  finish_time=NULL,updated_at=now()
                """, trade.getMchNo(), trade.getTradeNo(), "PROCESSING");
        return new PayTradeResultBo()
                .setOutOrderNo("SIM-" + trade.getTradeNo())
                .setComplete(false)
                .setPayBody("paylens-simulation://" + trade.getTradeNo());
    }
}
