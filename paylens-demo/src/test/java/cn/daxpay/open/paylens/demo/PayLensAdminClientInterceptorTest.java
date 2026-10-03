package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.platform.capability.auth.exception.NotLoginException;
import cn.daxpay.open.platform.core.enums.client.ClientEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayLensAdminClientInterceptorTest {
    @Test
    void acceptsAdminSession() {
        assertDoesNotThrow(() -> PayLensAdminClientInterceptor.requireAdminClient(ClientEnum.ADMIN.getCode()));
    }

    @Test
    void rejectsMerchantAndMissingClientSession() {
        assertThrows(NotLoginException.class,
                () -> PayLensAdminClientInterceptor.requireAdminClient(ClientEnum.MERCHANT.getCode()));
        assertThrows(NotLoginException.class, () -> PayLensAdminClientInterceptor.requireAdminClient(null));
    }
}
