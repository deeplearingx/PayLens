package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.analysis.DiagnosticAnalysisService;
import cn.daxpay.open.payment.admin.service.trade.AbnormalOrderAdminService;
import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.common.context.PaymentContext;
import cn.daxpay.open.payment.trade.abnormal.dao.AbnormalOrderManager;
import cn.daxpay.open.payment.trade.abnormal.entity.AbnormalOrder;
import cn.daxpay.open.payment.trade.order.dao.PayTradeManager;
import cn.daxpay.open.payment.trade.order.entity.PayTrade;
import cn.daxpay.open.payment.trade.runtime.service.sync.PaySyncService;
import cn.daxpay.open.platform.common.mybatisplus.extension.DaxLambdaQueryChainWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayLensControllerMerchantContextTest {
    private static final String DEMO_MERCHANT = "PAYLENS-DEMO";

    private final PayLensAnomalyFacade facade = mock(PayLensAnomalyFacade.class);
    private final ObjectProvider<DiagnosticAnalysisService> analysisServiceProvider = mock(ObjectProvider.class);
    private final AbnormalOrderManager abnormalOrderManager = mock(AbnormalOrderManager.class);
    private final AbnormalOrderAdminService abnormalOrderAdminService = mock(AbnormalOrderAdminService.class);
    private final PayTradeManager payTradeManager = mock(PayTradeManager.class);
    private final PaySyncService paySyncService = mock(PaySyncService.class);
    private final PaymentContext paymentContext = new PaymentContext();
    private final MerchantContextLoader merchantContextLoader = mock(MerchantContextLoader.class);
    private PayLensController controller;

    @BeforeEach
    void setUp() {
        controller = new PayLensController(facade, analysisServiceProvider, abnormalOrderManager,
                abnormalOrderAdminService, payTradeManager, paySyncService, paymentContext, merchantContextLoader);
        setDemoMerchant(DEMO_MERCHANT);
        doAnswer(invocation -> {
            paymentContext.setMchNo(DEMO_MERCHANT);
            return null;
        }).when(merchantContextLoader).initMch(DEMO_MERCHANT);
    }

    @Test
    void confirmSuccessRunsFreshSyncAndBusinessHandlingInConfiguredMerchantContext() {
        String tradeNo = "PAYLENS-TRADE";
        PayTrade trade = new PayTrade();
        trade.setTradeNo(tradeNo);
        trade.setMchNo(DEMO_MERCHANT);
        when(facade.detail(7L)).thenReturn(detail(tradeNo));
        when(payTradeManager.findByTradeNoNotTenant(tradeNo)).thenReturn(Optional.of(trade));
        when(paySyncService.syncPayOrderWithEvidence(trade)).thenReturn(
                new PaySyncService.SyncEvidence("sync-1", tradeNo, "success", OffsetDateTime.now()));
        doAnswer(invocation -> {
            assertEquals(DEMO_MERCHANT, paymentContext.getMchNo());
            return null;
        }).when(abnormalOrderAdminService).confirmSuccess(7L, "checked");

        controller.confirmSuccess(7L, new PayLensController.ActionRequest("checked"));

        verify(paySyncService).syncPayOrderWithEvidence(trade);
        verify(abnormalOrderAdminService).confirmSuccess(7L, "checked");
        assertFalse(paymentContext.isOpen(), "the helper must close a context it opened itself");
    }

    @Test
    void ignoreRunsBusinessHandlingInConfiguredMerchantContext() {
        when(facade.detail(8L)).thenReturn(detail("PAYLENS-TRADE"));
        doAnswer(invocation -> {
            assertEquals(DEMO_MERCHANT, paymentContext.getMchNo());
            return null;
        }).when(abnormalOrderAdminService).ignore(8L, "verified and ignored");

        controller.ignore(8L, new PayLensController.ActionRequest("verified and ignored"));

        verify(abnormalOrderAdminService).ignore(8L, "verified and ignored");
        assertFalse(paymentContext.isOpen(), "the helper must close a context it opened itself");
    }

    @Test
    void refusesActionIfMerchantContextDoesNotMatchConfiguredDemoMerchant() {
        when(facade.detail(9L)).thenReturn(detail("PAYLENS-TRADE"));
        doAnswer(invocation -> {
            paymentContext.setMchNo("OTHER-MERCHANT");
            return null;
        }).when(merchantContextLoader).initMch(DEMO_MERCHANT);

        assertThrows(IllegalStateException.class,
                () -> controller.ignore(9L, new PayLensController.ActionRequest("checked")));

        verify(abnormalOrderAdminService, never()).ignore(9L, "checked");
        assertFalse(paymentContext.isOpen());
    }

    @Test
    void refusesActionWhenDedicatedDemoMerchantIsNotConfigured() {
        setDemoMerchant(" ");
        when(facade.detail(10L)).thenReturn(detail("PAYLENS-TRADE"));

        assertThrows(IllegalStateException.class,
                () -> controller.ignore(10L, new PayLensController.ActionRequest("checked")));

        verify(merchantContextLoader, never()).initMch(anyString());
        verify(abnormalOrderAdminService, never()).ignore(10L, "checked");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pendingAnomalyListQueriesInsideConfiguredMerchantContext() {
        DaxLambdaQueryChainWrapper<AbnormalOrder> query = mock(DaxLambdaQueryChainWrapper.class);
        when(abnormalOrderManager.lambdaQuery()).thenAnswer(invocation -> {
            assertEquals(DEMO_MERCHANT, paymentContext.getMchNo());
            return query;
        });
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertEquals(List.of(), controller.anomalies().getData());

        verify(merchantContextLoader).initMch(DEMO_MERCHANT);
        assertFalse(paymentContext.isOpen(), "the query helper must close a context it opened itself");
    }

    private PayLensAnomalyFacade.AnomalyDetail detail(String tradeNo) {
        return new PayLensAnomalyFacade.AnomalyDetail(7L, tradeNo, null, "synthetic", 100L, "cny",
                "close_paid", "close", "close", "success", "pending", DEMO_MERCHANT,
                List.of(), List.of());
    }

    private void setDemoMerchant(String merchantNo) {
        try {
            Field field = PayLensController.class.getDeclaredField("demoMchNo");
            field.setAccessible(true);
            field.set(controller, merchantNo);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not configure the controller test merchant", e);
        }
    }
}
