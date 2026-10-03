package cn.daxpay.open.platform.iam.auth.service;

import cn.daxpay.open.platform.iam.param.auth.LoginContentParam;
import cn.daxpay.open.platform.iam.service.social.SocialAutoLoginConfigService;
import cn.daxpay.open.platform.system.entity.config.platform.security.PlatformLoginSecurityConfig;
import cn.daxpay.open.platform.system.entity.config.platform.security.PlatformWebAuthnConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginContentServiceTest {

    @Mock
    private IamSecurityConfigService iamSecurityConfigService;

    @Mock
    private SocialAutoLoginConfigService socialAutoLoginConfigService;

    @Test
    @DisplayName("登录上下文声明 RSA 加密，与服务端密码解密流程一致")
    void getLoginContent_shouldAdvertiseEncryptedPassword() {
        when(iamSecurityConfigService.getLoginSecurity())
                .thenReturn(new PlatformLoginSecurityConfig().setCaptchaEnabled(false));
        when(iamSecurityConfigService.getWebAuthnConfig())
                .thenReturn(new PlatformWebAuthnConfig().setEnabled(false));

        LoginContentService service = new LoginContentService(
                List.of(), iamSecurityConfigService, socialAutoLoginConfigService);
        var result = service.getLoginContent(new LoginContentParam());

        assertTrue(result.isPasswordEncrypted());
        verify(iamSecurityConfigService).getLoginSecurity();
        verify(iamSecurityConfigService).getWebAuthnConfig();
        verifyNoInteractions(socialAutoLoginConfigService);
    }
}
