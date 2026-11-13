package org.jetlinks.community.things.data;

/** Extremes of finite numeric samples, with the earliest original sample timestamps. */
public record PropertyExtremes(String thingId, String property, Status status,
                              Double minValue, Long minTime, Double maxValue, Long maxTime) {
    public enum Status { OK, NO_DATA, UNSUPPORTED_STORAGE }

    public record Target(String thingId, String property) { }

    public Target target() {
        return new Target(thingId, property);
    }

    public static PropertyExtremes empty(Target target, Status status) {
        return new PropertyExtremes(target.thingId(), target.property(), status, null, null, null, null);
    }
}
