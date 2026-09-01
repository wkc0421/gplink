package gp.saas.apitoken.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenPermissionCatalogTest {

    @Test
    void exposesOnlyPublicApiResources() {
        assertFalse(ApiTokenPermissionCatalog.supports("api-token", "save"));
        assertFalse(ApiTokenPermissionCatalog.supports("authorization-api", "save"));
        assertTrue(ApiTokenPermissionCatalog.supports("device-instance", "query"));
        assertTrue(ApiTokenPermissionCatalog.supports("system-operation", "soft-restart"));
        assertFalse(ApiTokenPermissionCatalog.supports("system-operation", "save"));
    }

    @Test
    void resolvesPathsUsingTheSameCatalogShownToAdministrators() {
        var query = ApiTokenPermissionCatalog
            .resolve("/api/v1/device/_query", HttpMethod.POST)
            .orElseThrow();
        assertEquals("device-instance", query.resource());
        assertEquals("query", query.action());

        var delete = ApiTokenPermissionCatalog
            .resolve("/api/v1/mqtt/forward/subscription/lease", HttpMethod.DELETE)
            .orElseThrow();
        assertEquals("mqtt-forward-subscription", delete.resource());
        assertEquals("delete", delete.action());

        var highRisk = ApiTokenPermissionCatalog
            .resolve("/api/v1/system/memory-analysis", HttpMethod.GET)
            .orElseThrow();
        assertEquals("system-operation", highRisk.resource());
        assertEquals("memory-analysis", highRisk.action());

        var redis = ApiTokenPermissionCatalog
            .resolve("/api/v1/device-data/devices/properties/redis", HttpMethod.POST)
            .orElseThrow();
        assertEquals("device-opt-api", redis.resource());
        assertEquals("query", redis.action());

        var history = ApiTokenPermissionCatalog
            .resolve("/api/v1/device-data/242511010234/U/history", HttpMethod.POST)
            .orElseThrow();
        assertEquals("device-opt-api", history.resource());
        assertEquals("query", history.action());

        var deviceTest = ApiTokenPermissionCatalog
            .resolve("/api/v1/device/test", HttpMethod.POST)
            .orElseThrow();
        assertEquals("device-instance", deviceTest.resource());
        assertEquals("query", deviceTest.action());

        var deviceBind = ApiTokenPermissionCatalog
            .resolve("/api/v1/device/bind", HttpMethod.GET)
            .orElseThrow();
        assertEquals("device-instance", deviceBind.resource());
        assertEquals("save", deviceBind.action());

        var alarmUnbind = ApiTokenPermissionCatalog
            .resolve("/api/v1/alarm/config/alarm-1/_unbind", HttpMethod.GET)
            .orElseThrow();
        assertEquals("alarm-config", alarmUnbind.resource());
        assertEquals("delete", alarmUnbind.action());
    }
}
