package gp.saas.apitoken.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ApiTokenGrantOptions {
    private final List<ResourceOption> resources;
    private final long defaultLifetimeMs;
    private final long minLifetimeMs;
    private final long maxLifetimeMs;

    @Getter
    @AllArgsConstructor
    public static class ResourceOption {
        private final String id;
        private final String name;
        private final String group;
        private final String description;
        private final boolean highRisk;
        private final List<ActionOption> actions;
    }

    @Getter
    @AllArgsConstructor
    public static class ActionOption {
        private final String id;
        private final String name;
        private final String description;
    }
}
