package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.platform.core.annotation.PermCode;
import cn.daxpay.open.platform.core.code.PermCodes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@PermCode(menuCode = PermCodes.Trade.AbnormalOrder.MENU)
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
@Controller
public class PayLensPageController {
    @GetMapping("/paylens")
    @PermCode(code = PermCodes.Action.VIEW)
    public String index() { return "paylens/index"; }
}
