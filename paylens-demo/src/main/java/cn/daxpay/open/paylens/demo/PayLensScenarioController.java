package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.platform.core.annotation.PermCode;
import cn.daxpay.open.platform.core.code.PermCodes;
import cn.daxpay.open.platform.core.rest.Res;
import cn.daxpay.open.platform.core.rest.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@PermCode(menuCode = PermCodes.Trade.AbnormalOrder.MENU)
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/paylens/api/scenarios")
@RequiredArgsConstructor
public class PayLensScenarioController {
    private final PayLensScenarioService scenarioService;

    @PostMapping
    @PermCode(code = PermCodes.Action.MANAGE)
    public Result<PayLensScenarioService.ScenarioResult> create(@RequestBody ScenarioRequest request) {
        return Res.ok(scenarioService.run(request.scenario()));
    }

    @PostMapping("/{tradeNo}/replay-callback")
    @PermCode(code = PermCodes.Action.MANAGE)
    public Result<PayLensScenarioService.ScenarioResult> replay(@PathVariable String tradeNo) {
        return Res.ok(scenarioService.replaySuccessCallback(tradeNo));
    }

    public record ScenarioRequest(String scenario) {}
}
