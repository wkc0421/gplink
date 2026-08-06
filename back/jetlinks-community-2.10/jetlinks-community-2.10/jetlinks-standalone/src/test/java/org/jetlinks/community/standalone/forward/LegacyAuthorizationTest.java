package org.jetlinks.community.standalone.forward;

import gp.saas.legacy.web.LegacyAlarmController;
import gp.saas.legacy.web.LegacyDeviceController;
import gp.saas.legacy.web.LegacyDeviceDataController;
import gp.saas.legacy.web.LegacyProductController;
import gp.saas.legacy.web.LegacyRuleSceneController;
import gp.saas.legacy.web.LegacySystemController;
import gp.saas.web.ApiAuthorizationController;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.community.saas.changeproperty.web.ChangePropertyConfigController;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAuthorizationTest {

    @Test
    void shouldRequireAuthorizationForLegacyControllers() {
        List<Class<?>> controllers = List.of(
            LegacyAlarmController.class,
            LegacyDeviceController.class,
            LegacyDeviceDataController.class,
            LegacyProductController.class,
            LegacyRuleSceneController.class,
            LegacySystemController.class,
            LegacyDeviceMqttSubscriberController.class,
            ChangePropertyConfigController.class
        );

        for (Class<?> controller : controllers) {
            Authorize authorize = controller.getAnnotation(Authorize.class);
            assertNotNull(authorize, controller.getName() + " must declare @Authorize");
            assertFalse(authorize.ignore(), controller.getName() + " must not ignore authorization");
        }
    }

    @Test
    void shouldKeepTokenEndpointAnonymous() throws NoSuchMethodException {
        Authorize authorize = ApiAuthorizationController.class
            .getMethod("getToken", gp.saas.entity.TokenRequestEntity.class)
            .getAnnotation(Authorize.class);

        assertNotNull(authorize);
        assertTrue(authorize.ignore());
    }
}
