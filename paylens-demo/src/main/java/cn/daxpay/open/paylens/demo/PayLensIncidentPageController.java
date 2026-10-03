package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.platform.core.annotation.PermCode;
import cn.daxpay.open.platform.core.code.PermCodes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@PermCode(menuCode = PermCodes.Trade.AbnormalOrder.MENU)
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
@Controller
public class PayLensIncidentPageController {
    @GetMapping("/paylens/incidents")
    @PermCode(code = PermCodes.Action.VIEW)
    public String incidents() { return "paylens/incidents"; }
}
