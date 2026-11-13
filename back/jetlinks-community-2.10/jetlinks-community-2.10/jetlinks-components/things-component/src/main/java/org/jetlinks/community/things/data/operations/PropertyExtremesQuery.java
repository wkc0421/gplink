package org.jetlinks.community.things.data.operations;

import org.jetlinks.community.things.data.PropertyExtremes;
import reactor.core.publisher.Flux;

import java.util.List;

/** Resolved storage capability. Callers must authorize every target before using it. */
public interface PropertyExtremesQuery {
    int MAX_BATCH_SIZE = 1000;
    int MAX_CONCURRENCY = 5;

    /** Equal keys must identify the same database, physical table and query semantics. */
    Object storageKey();

    /** Targets must belong to this storage key. Returns one row per target, including empty ones. */
    Flux<PropertyExtremes> query(List<PropertyExtremes.Target> targets, long startTime, long endTime);
}
