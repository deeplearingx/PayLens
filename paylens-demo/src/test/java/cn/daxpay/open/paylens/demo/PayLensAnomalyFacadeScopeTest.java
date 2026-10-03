package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.common.context.PaymentContext;
import cn.daxpay.open.payment.trade.abnormal.dao.AbnormalOrderManager;
import cn.daxpay.open.payment.trade.abnormal.entity.AbnormalOrder;
import cn.daxpay.open.payment.trade.order.dao.PayTradeManager;
import cn.daxpay.open.payment.trade.order.entity.PayTrade;
import cn.daxpay.open.payment.trade.record.dao.PayCallbackRecordManager;
import cn.daxpay.open.payment.trade.record.dao.PaySyncRecordManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayLensAnomalyFacadeScopeTest {
    @Test
    void abnormalDetailsFromAnotherMerchantAreIndistinguishableFromMissing() throws Exception {
        AbnormalOrderManager abnormalOrders = mock(AbnormalOrderManager.class);
        PayTradeManager trades = mock(PayTradeManager.class);
        PaymentContext paymentContext = new PaymentContext();
        MerchantContextLoader merchantContextLoader = mock(MerchantContextLoader.class);
        doAnswer(invocation -> {
            paymentContext.setMchNo("M-DEMO");
            return null;
        }).when(merchantContextLoader).initMch("M-DEMO");
        PayLensAnomalyFacade facade = new PayLensAnomalyFacade(abnormalOrders, trades,
                mock(PayCallbackRecordManager.class), mock(PaySyncRecordManager.class),
                mock(JdbcTemplate.class), paymentContext, merchantContextLoader);
        Field demoMerchant = PayLensAnomalyFacade.class.getDeclaredField("demoMchNo");
        demoMerchant.setAccessible(true);
        demoMerchant.set(facade, "M-DEMO");
        AbnormalOrder otherMerchantOrder = new AbnormalOrder();
        otherMerchantOrder.setMchNo("M-OTHER");
        when(abnormalOrders.findById(19L)).thenAnswer(invocation -> {
            assertEquals("M-DEMO", paymentContext.getMchNo());
            return Optional.of(otherMerchantOrder);
        });

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> facade.detail(19L));

        assertEquals("异常订单不存在或无权访问", error.getMessage());
        verifyNoInteractions(trades);
    }

    @Test
    void demoTradeDetailsFromAnotherMerchantAreIndistinguishableFromMissing() throws Exception {
        AbnormalOrderManager abnormalOrders = mock(AbnormalOrderManager.class);
        PayTradeManager trades = mock(PayTradeManager.class);
        PaymentContext paymentContext = new PaymentContext();
        MerchantContextLoader merchantContextLoader = mock(MerchantContextLoader.class);
        doAnswer(invocation -> {
            paymentContext.setMchNo("M-DEMO");
            return null;
        }).when(merchantContextLoader).initMch("M-DEMO");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PayLensAnomalyFacade facade = new PayLensAnomalyFacade(abnormalOrders, trades,
                mock(PayCallbackRecordManager.class), mock(PaySyncRecordManager.class),
                jdbc, paymentContext, merchantContextLoader);
        Field demoMerchant = PayLensAnomalyFacade.class.getDeclaredField("demoMchNo");
        demoMerchant.setAccessible(true);
        demoMerchant.set(facade, "M-DEMO");
        PayTrade otherMerchantTrade = new PayTrade();
        otherMerchantTrade.setTradeNo("T-OTHER");
        otherMerchantTrade.setMchNo("M-OTHER");
        when(trades.findByTradeNoNotTenant("T-OTHER")).thenReturn(Optional.of(otherMerchantTrade));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.detailForDemoTrade("T-OTHER"));

        assertEquals("演示交易不存在或无权访问", error.getMessage());
        verifyNoInteractions(abnormalOrders, jdbc);
    }
}
