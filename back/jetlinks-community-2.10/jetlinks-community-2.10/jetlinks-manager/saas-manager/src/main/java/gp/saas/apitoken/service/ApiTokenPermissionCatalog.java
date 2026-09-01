package gp.saas.apitoken.service;

import gp.saas.apitoken.dto.ApiTokenGrantOptions.ActionOption;
import gp.saas.apitoken.dto.ApiTokenGrantOptions.ResourceOption;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Single source of truth for permissions that may be assigned to public API tokens. */
public final class ApiTokenPermissionCatalog {

    private ApiTokenPermissionCatalog() {
    }

    private static ActionOption action(String id, String name, String description) {
        return new ActionOption(id, name, description);
    }

    private static final ActionOption QUERY = action("query", "查询", "读取详情、列表和状态数据");
    private static final ActionOption SAVE = action("save", "写入", "新增、修改、调用或其他写入操作");
    private static final ActionOption DELETE = action("delete", "删除", "删除资源或数据");

    private static final List<ResourceOption> RESOURCES = List.of(
        new ResourceOption("device-product", "产品", "产品管理", "产品详情、列表、物模型以及发布相关接口", false, List.of(QUERY, SAVE)),
        new ResourceOption("device-instance", "设备", "设备管理", "设备详情、列表、属性读取、功能调用和维护接口", false, List.of(QUERY, SAVE, DELETE)),
        new ResourceOption("device-opt-api", "设备数据", "设备数据", "历史数据、实时数据、MQTT 订阅和属性变化配置", false, List.of(QUERY, SAVE)),
        new ResourceOption("alarm-config", "告警", "告警管理", "告警配置、同步、启停和绑定接口", false, List.of(QUERY, SAVE, DELETE)),
        new ResourceOption("rule-scene", "场景", "场景联动", "场景配置、启停、属性读取和功能调用接口", false, List.of(QUERY, SAVE, DELETE)),
        new ResourceOption("mqtt-forward-subscription", "MQTT 转发", "MQTT", "临时转发订阅的创建、续期、查询和关闭", false, List.of(QUERY, SAVE, DELETE)),
        new ResourceOption("system-operation", "系统操作", "系统操作", "直接影响服务运行状态的高危操作", true, List.of(
            action("gc", "执行 GC", "触发服务端垃圾回收"),
            action("soft-restart", "软重启", "重新初始化应用运行状态"),
            action("memory-analysis", "内存分析", "生成内存分析数据")
        ))
    );

    private static final Map<String, ResourceOption> RESOURCE_MAP = createResourceMap();

    private static Map<String, ResourceOption> createResourceMap() {
        Map<String, ResourceOption> resources = new LinkedHashMap<>();
        RESOURCES.forEach(resource -> resources.put(resource.getId(), resource));
        return resources;
    }

    public static List<ResourceOption> resources() {
        return RESOURCES;
    }

    public static boolean supports(String resource, String action) {
        ResourceOption option = RESOURCE_MAP.get(resource);
        return option != null && option.getActions().stream().anyMatch(item -> item.getId().equals(action));
    }

    public static Optional<PermissionTarget> resolve(String path, HttpMethod method) {
        String resource;
        if (path.startsWith("/api/v1/device-data")) resource = "device-opt-api";
        else if (path.startsWith("/api/v1/device")) resource = "device-instance";
        else if (path.startsWith("/api/v1/product")) resource = "device-product";
        else if (path.startsWith("/api/v1/alarm")) resource = "alarm-config";
        else if (path.startsWith("/api/v1/rule/scene")) resource = "rule-scene";
        else if (path.startsWith("/api/v1/mqtt")) resource = path.contains("forward/subscription") ? "mqtt-forward-subscription" : "device-opt-api";
        else if (path.startsWith("/api/v1/change-property")) resource = "device-opt-api";
        else if (path.startsWith("/api/v1/system")) resource = "system-operation";
        else return Optional.empty();

        String action;
        if (path.endsWith("/gc")) action = "gc";
        else if (path.endsWith("/soft-restart")) action = "soft-restart";
        else if (path.endsWith("/memory-analysis")) action = "memory-analysis";
        else if (isLegacyDeletePath(path, method)) action = "delete";
        else if (isLegacySavePath(path, method)) action = "save";
        else if (isLegacyQueryPath(path, method)) action = "query";
        else if (HttpMethod.DELETE.equals(method)) action = "delete";
        else action = "save";
        return Optional.of(new PermissionTarget(resource, action));
    }

    private static boolean isLegacyQueryPath(String path, HttpMethod method) {
        if (HttpMethod.GET.equals(method) || path.contains("/_query")) {
            return true;
        }
        if (path.startsWith("/api/v1/device-data/")) {
            return path.contains("/history")
                || path.endsWith("/devices/properties/es")
                || path.endsWith("/devices/properties/pg")
                || path.endsWith("/devices/properties/redis")
                || path.endsWith("/devices/interval")
                || path.contains("/devices/interval/");
        }
        return "/api/v1/device/test".equals(path);
    }

    private static boolean isLegacyDeletePath(String path, HttpMethod method) {
        return HttpMethod.DELETE.equals(method)
            || path.startsWith("/api/v1/device/product/")
            || path.endsWith("/config/_unbind")
            || path.endsWith("/_unbind");
    }

    private static boolean isLegacySavePath(String path, HttpMethod method) {
        return path.equals("/api/v1/device/bind")
            || path.endsWith("/_enable")
            || path.endsWith("/_disable")
            || path.endsWith("/_bind");
    }

    public record PermissionTarget(String resource, String action) {
    }
}
