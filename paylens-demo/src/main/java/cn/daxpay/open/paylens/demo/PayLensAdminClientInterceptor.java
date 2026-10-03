package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.platform.capability.auth.exception.NotLoginException;
import cn.daxpay.open.platform.capability.auth.util.SecurityUtil;
import cn.daxpay.open.platform.core.code.CommonErrorCode;
import cn.daxpay.open.platform.core.enums.client.ClientEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Restricts the PayLens workbench to the authenticated operations client. */
public class PayLensAdminClientInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionClientCode = SecurityUtil.getUser().getClientCode();
        requireAdminClient(sessionClientCode);
        return true;
    }

    static void requireAdminClient(String sessionClientCode) {
        if (!ClientEnum.ADMIN.getCode().equals(sessionClientCode)) {
            throw new NotLoginException(CommonErrorCode.AUTHENTICATION_FAIL, "error.auth.clientCodeMismatch");
        }
    }
}
