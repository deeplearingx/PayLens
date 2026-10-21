package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.platform.core.annotation.PermCode;
import cn.daxpay.open.platform.core.code.PermCodes;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayLensPermissionContractTest {
    private static final List<Class<?>> CONTROLLERS = List.of(
            PayLensController.class, PayLensScenarioController.class, PayLensPageController.class,
            PayLensIncidentController.class);

    @Test
    void everyPayLensRouteRequiresTheAdminMenuAndExplicitReadOrManagePermission() {
        int routeCount = 0;
        for (Class<?> controller : CONTROLLERS) {
            PermCode classPermission = controller.getDeclaredAnnotation(PermCode.class);
            assertNotNull(classPermission, controller.getSimpleName() + " must be restricted to the PayLens menu");
            assertEquals(PermCodes.Trade.AbnormalOrder.MENU, classPermission.menuCode());

            for (Method method : controller.getDeclaredMethods()) {
                if (!isRoute(method)) continue;
                routeCount++;
                PermCode actionPermission = method.getDeclaredAnnotation(PermCode.class);
                assertNotNull(actionPermission, controller.getSimpleName() + "." + method.getName() + " has no action permission");
                assertTrue(List.of(PermCodes.Action.VIEW, PermCodes.Action.MANAGE).contains(actionPermission.code()),
                        controller.getSimpleName() + "." + method.getName() + " must be read-only or manage-gated");
            }
        }
        assertTrue(routeCount >= 20, "the contract should cover every current PayLens endpoint");
    }

    @Test
    void suggestionsAreViewOnlyAndManualMergeRequiresManagePermission() throws Exception {
        assertEquals(PermCodes.Action.VIEW,
                PayLensIncidentController.class.getDeclaredMethod("mergeSuggestions", String.class)
                        .getDeclaredAnnotation(PermCode.class).code());
        assertEquals(PermCodes.Action.MANAGE,
                PayLensIncidentController.class.getDeclaredMethod("merge", PayLensIncidentController.MergeRequest.class)
                        .getDeclaredAnnotation(PermCode.class).code());
    }

    private boolean isRoute(Method method) {
        return method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class);
    }
}
