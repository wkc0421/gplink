/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.things.data.operations;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.ezorm.core.dsl.Query;
import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.things.Thing;
import org.jetlinks.core.things.ThingMetadata;
import org.jetlinks.core.things.ThingTemplate;
import org.jetlinks.core.things.ThingsRegistry;
import org.jetlinks.community.things.data.*;
import org.jetlinks.community.timeseries.TimeSeriesData;
import org.jetlinks.community.timeseries.query.AggregationData;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@AllArgsConstructor
public abstract class AbstractQueryOperations implements QueryOperations {

    protected final String thingType;
    protected final String thingTemplateId;
    protected final String thingId;
    protected final MetricBuilder metricBuilder;
    protected final DataSettings settings;
    protected final ThingsRegistry registry;


    protected abstract Flux<TimeSeriesData> doQuery(String metric, Query<?, QueryParamEntity> query);

    protected abstract <T> Mono<PagerResult<T>> doQueryPage(String metric,
                                                            Query<?, QueryParamEntity> query,
                                                            Function<TimeSeriesData, T> mapper);

    protected abstract Flux<AggregationData> doAggregation(String metric, AggregationRequest request, AggregationContext context);


    protected Mono<ThingMetadata> getMetadata() {
        //指定了物实例
        if (StringUtils.hasText(thingId)) {
            return registry
                .getThing(thingType, thingId)
                .flatMap(Thing::getMetadata);
        }
        return registry
            .getTemplate(thingType, thingTemplateId)
            .flatMap(ThingTemplate::getMetadata);
    }

    protected void applyQuery(Query<?, ? extends QueryParam> query) {

        //默认时间戳倒序
        if (CollectionUtils.isEmpty(query.getParam().getSorts())) {
            query.orderByDesc(ThingsDataConstants.COLUMN_TIMESTAMP);
        }
        //指定物实例，追加条件: where thingId = ?
        if (StringUtils.hasText(thingId)) {
            query.and(metricBuilder.getThingIdProperty(), thingId);
        }

    }


    protected abstract Flux<ThingPropertyDetail> queryProperty(@Nonnull QueryParamEntity param,
                                                               @Nonnull ThingMetadata metadata,
                                                               @Nonnull Map<String, PropertyMetadata> properties);

    protected abstract Mono<PagerResult<ThingPropertyDetail>> queryPropertyPage(@Nonnull QueryParamEntity param,
                                                                                @Nonnull ThingMetadata metadata,
                                                                                @Nonnull Map<String, PropertyMetadata> properties);


    protected abstract Flux<ThingPropertyDetail> queryEachProperty(@Nonnull String metric,
                                                                   @Nonnull Query<?, QueryParamEntity> query,
                                                                   @Nonnull ThingMetadata metadata,
                                                                   @Nonnull Map<String, PropertyMetadata> properties);

    /**
     * Generic fallback for stores without an indexed boundary-query primitive.
     * It opens one descending stream, folds it in O(rows + boundaries), and
     * cancels as soon as a non-null baseline at/before startTime is found.
     * A missing outer end gets one ascending lookup for the first reading after it.
     */
    protected Mono<PropertyBoundaryValues> queryPropertyAtBoundaries(@Nonnull String metric,
                                                                      long startTime,
                                                                      long endTime,
                                                                      @Nonnull List<Long> boundaries,
                                                                      @Nonnull ThingMetadata metadata,
                                                                      @Nonnull PropertyMetadata property) {
        QueryParamEntity query = QueryParamEntity.of();
        query.and(ThingsDataConstants.COLUMN_TIMESTAMP, "lte", endTime);
        query.setOrderBy(ThingsDataConstants.COLUMN_TIMESTAMP + " desc");
        query.setPaging(false);
        return foldBoundaryValues(
            property.getId(), startTime, endTime, boundaries,
            queryProperty(query, metadata, Collections.singletonMap(property.getId(), property)))
            .flatMap(values -> {
                boolean missingEnd = values.getValues().stream()
                    .anyMatch(value -> value.getBoundary() == endTime && value.getValue() == null);
                long now = System.currentTimeMillis();
                if (!missingEnd || endTime >= now) {
                    return Mono.just(values);
                }
                QueryParamEntity after = QueryParamEntity.of();
                after.and(ThingsDataConstants.COLUMN_TIMESTAMP, "gt", endTime);
                after.and(ThingsDataConstants.COLUMN_TIMESTAMP, "lte", now);
                after.setOrderBy(ThingsDataConstants.COLUMN_TIMESTAMP + " asc");
                after.setPaging(false);
                return queryProperty(after, metadata, Collections.singletonMap(property.getId(), property))
                    .filter(value -> value.getValue() != null)
                    .next()
                    .map(first -> withEndValue(values, first.getValue()))
                    .defaultIfEmpty(values);
            });
    }

    private static PropertyBoundaryValues withEndValue(PropertyBoundaryValues values, Object endValue) {
        List<PropertyBoundaryValues.BoundaryValue> boundaries = values.getValues().stream()
            .map(value -> new PropertyBoundaryValues.BoundaryValue(value.getBoundary(),
                value.getBoundary() == values.getEndTime() ? endValue : value.getValue()))
            .collect(Collectors.toList());
        return new PropertyBoundaryValues(values.getProperty(), values.getStartTime(), values.getEndTime(),
            values.getInitialValue(), boundaries);
    }

    static Mono<PropertyBoundaryValues> foldBoundaryValues(String property,
                                                            long startTime,
                                                            long endTime,
                                                            List<Long> boundaries,
                                                            Flux<ThingPropertyDetail> source) {
        BoundaryAccumulator accumulator = new BoundaryAccumulator(
            property, startTime, endTime, boundaries);
        return source
            .takeUntil(accumulator::accept)
            .then(Mono.fromSupplier(accumulator::finish));
    }

    @Nonnull
    @Override
    public final Mono<PropertyBoundaryValues> queryPropertyAtBoundaries(long startTime,
                                                                         long endTime,
                                                                         @Nonnull List<Long> boundaries,
                                                                         @Nonnull String property) {
        validateBoundaryRequest(startTime, endTime, boundaries, property);
        String metric = metricBuilder.createPropertyMetric(thingType, thingTemplateId, thingId);
        List<Long> requested = Collections.unmodifiableList(new ArrayList<>(boundaries));
        return getMetadata()
            .flatMap(metadata -> {
                PropertyMetadata propertyMetadata = metadata.getPropertyOrNull(property);
                if (propertyMetadata == null) {
                    return Mono.just(PropertyBoundaryValues.empty(
                        property, startTime, endTime, requested));
                }
                return queryPropertyAtBoundaries(
                    metric, startTime, endTime, requested, metadata, propertyMetadata);
            })
            .defaultIfEmpty(PropertyBoundaryValues.empty(property, startTime, endTime, requested));
    }

    private static void validateBoundaryRequest(long startTime,
                                                long endTime,
                                                List<Long> boundaries,
                                                String property) {
        if (!StringUtils.hasText(property)) {
            throw new IllegalArgumentException("property must not be empty");
        }
        if (endTime < startTime) {
            throw new IllegalArgumentException("endTime must be greater than or equal to startTime");
        }
        if (boundaries == null || boundaries.isEmpty()) {
            throw new IllegalArgumentException("boundaries must not be empty");
        }
        for (Long boundary : boundaries) {
            if (boundary == null || boundary < startTime || boundary > endTime) {
                throw new IllegalArgumentException("boundary must be within [startTime,endTime]");
            }
        }
    }

    private static final class BoundaryAccumulator {
        private final String property;
        private final long startTime;
        private final long endTime;
        private final List<Long> requested;
        private final List<Long> descending;
        private final Map<Long, Object> values = new HashMap<>();
        private int cursor;
        private Object baseline;
        private Object firstInRange;
        private boolean baselineFound;

        private BoundaryAccumulator(String property,
                                    long startTime,
                                    long endTime,
                                    List<Long> requested) {
            this.property = property;
            this.startTime = startTime;
            this.endTime = endTime;
            this.requested = requested;
            TreeSet<Long> ordered = new TreeSet<>(Comparator.reverseOrder());
            ordered.addAll(requested);
            this.descending = new ArrayList<>(ordered);
        }

        private boolean accept(ThingPropertyDetail detail) {
            Object value = detail.getValue();
            if (value == null) {
                return false;
            }
            long timestamp = detail.getTimestamp();
            while (cursor < descending.size() && timestamp <= descending.get(cursor)) {
                values.put(descending.get(cursor++), value);
            }
            if (timestamp >= startTime && timestamp <= endTime) {
                // The stream is descending, so the final assignment is the first in-range value.
                firstInRange = value;
            }
            if (timestamp <= startTime) {
                baseline = value;
                baselineFound = true;
                return true;
            }
            return false;
        }

        private PropertyBoundaryValues finish() {
            List<PropertyBoundaryValues.BoundaryValue> result = new ArrayList<>(requested.size());
            for (Long boundary : requested) {
                // A pre-range baseline is a start fallback, not an observed end reading.
                Object value = boundary == endTime && firstInRange == null ? null : values.get(boundary);
                result.add(new PropertyBoundaryValues.BoundaryValue(boundary, value));
            }
            return new PropertyBoundaryValues(
                property, startTime, endTime, baselineFound ? baseline : firstInRange, result);
        }
    }

    @Nonnull
    @Override
    public final Flux<ThingPropertyDetail> queryEachProperty(@Nonnull QueryParamEntity param,
                                                             @Nonnull String... property) {

        return this
            .getMetadata()
            .flatMapMany(metadata -> {
                Map<String, PropertyMetadata> properties = getProperties(metadata, property);
                if (properties.isEmpty()) {
                    return Mono.empty();
                }
                if (properties.size() == 1) {
                    return queryProperty(param, metadata, properties);
                }
                String metric = metricBuilder.createPropertyMetric(thingType, thingTemplateId, thingId);
                Query<?, QueryParamEntity> query = param.toNestQuery(this::applyQuery);
                return queryEachProperty(metric, query, metadata, properties);
            });
    }

    @Nonnull
    @Override
    public final Flux<ThingPropertyDetail> queryProperty(@Nonnull QueryParamEntity query,
                                                         @Nonnull String... property) {


        return this
            .getMetadata()
            .flatMapMany(metadata -> {
                Map<String, PropertyMetadata> properties = getProperties(metadata, property);
                if (properties.isEmpty()) {
                    return Mono.empty();
                }
                return queryProperty(query.clone(), metadata, properties);
            });
    }

    @Nonnull
    @Override
    public final Mono<PagerResult<ThingPropertyDetail>> queryPropertyPage(@Nonnull QueryParamEntity query,
                                                                          @Nonnull String... property) {
        return this
            .getMetadata()
            .flatMap(metadata -> {
                Map<String, PropertyMetadata> properties = getProperties(metadata, property);
                if (properties.isEmpty()) {
                    return Mono.empty();
                }
                return queryPropertyPage(query.clone(), metadata, properties);
            })
            .defaultIfEmpty(PagerResult.of(0, new ArrayList<>(), query));
    }

    private Map<String, PropertyMetadata> getProperties(ThingMetadata metadata, String... property) {
        if (property.length == 0) {
            return metadata
                .getProperties()
                .stream()
                .collect(Collectors.toMap(PropertyMetadata::getId, Function.identity()));
        }
        Set<String> propertiesFilter = property.length == 1
            ? Collections.singleton(property[0])
            : new HashSet<>(Arrays.asList(property));
        return metadata
            .getProperties()
            .stream()
            .filter(p -> propertiesFilter.contains(p.getId()))
            .collect(Collectors.toMap(PropertyMetadata::getId, Function.identity()));
    }

    @Nonnull
    @Override
    public final Flux<AggregationData> aggregationProperties(@Nonnull AggregationRequest request,
                                                             @Nonnull PropertyAggregation... properties) {
        String metric = metricBuilder.createPropertyMetric(thingType, thingTemplateId, thingId);

        AggregationRequest aggRequest = request.copy();

        aggRequest.getFilter().toNestQuery(this::applyQuery);

        return getMetadata()
            .flatMapMany(metadata -> doAggregation(metric, aggRequest, new AggregationContext(metadata, properties)));
    }


    @Override
    public Flux<ThingMessageLog> queryMessageLog(@Nonnull QueryParamEntity param) {
        String metric = metricBuilder.createLogMetric(thingType, thingTemplateId, thingId);
        Query<?, QueryParamEntity> query = param.toNestQuery(this::applyQuery);
        return doQuery(metric, query)
            .map(data -> ThingMessageLog.of(data, metricBuilder.getThingIdProperty()));
    }

    @Override
    public Mono<PagerResult<ThingMessageLog>> queryMessageLogPage(@Nonnull QueryParamEntity param) {
        String metric = metricBuilder.createLogMetric(thingType, thingTemplateId, thingId);
        Query<?, QueryParamEntity> query = param.toNestQuery(this::applyQuery);
        return doQueryPage(metric, query, data -> ThingMessageLog.of(data, metricBuilder.getThingIdProperty()));
    }

    @Nonnull
    @Override
    public Mono<PagerResult<ThingEvent>> queryEventPage(@Nonnull String eventId,
                                                        @Nonnull QueryParamEntity param,
                                                        boolean format) {
        Query<?, QueryParamEntity> query = param.toNestQuery(this::applyQuery);
        String metric;
        if (settings.getEvent().eventIsAllInOne()) {
            metric = metricBuilder.createEventAllInOneMetric(thingType, thingTemplateId, thingId);
            query.and(ThingsDataConstants.COLUMN_EVENT_ID, eventId);
        } else {
            metric = metricBuilder.createEventMetric(thingType, thingTemplateId, thingId, eventId);
        }
        if (format) {
            return getMetadata()
                .mapNotNull(metadata -> metadata.getEventOrNull(eventId))
                .flatMap(metadata -> doQueryPage(metric, query, data -> ThingEvent
                    .of(data, metricBuilder.getThingIdProperty())
                    .putFormat(metadata)))
                .defaultIfEmpty(PagerResult.of(0, new ArrayList<>(), param));
        }
        return doQueryPage(metric, query, data -> ThingEvent.of(data, metricBuilder.getThingIdProperty()))
            .defaultIfEmpty(PagerResult.of(0, new ArrayList<>(), param));
    }

    @Nonnull
    @Override
    public final Flux<ThingEvent> queryEvent(@Nonnull String eventId, @Nonnull QueryParamEntity param, boolean format) {
        Query<?, QueryParamEntity> query = param.toNestQuery(this::applyQuery);
        String metric;
        if (settings.getEvent().eventIsAllInOne()) {
            metric = metricBuilder.createEventAllInOneMetric(thingType, thingTemplateId, thingId);
            query.and(ThingsDataConstants.COLUMN_EVENT_ID, eventId);
        } else {
            metric = metricBuilder.createEventMetric(thingType, thingTemplateId, thingId, eventId);
        }
        if (format) {
            return getMetadata()
                .mapNotNull(metadata -> metadata.getEventOrNull(eventId))
                .flatMapMany(metadata -> doQuery(metric, query).map(data -> ThingEvent
                    .of(data, metricBuilder.getThingIdProperty())
                    .putFormat(metadata)));
        }
        return doQuery(metric, query)
            .map(data -> ThingEvent.of(data, metricBuilder.getThingIdProperty()));
    }

    @Getter
    protected static class AggregationContext {

        private final Map<String, String> propertyAlias;
        private final Map<String, PropertyAggregation> aliasToProperty;

        private final PropertyAggregation[] properties;
        private final ThingMetadata metadata;

        public AggregationContext(ThingMetadata metadata, PropertyAggregation... properties) {
            this.metadata = metadata;
            this.properties = properties;
            Set<String> aliases = new HashSet<>();
            for (PropertyAggregation property : properties) {
                if (property == null || !StringUtils.hasText(property.getProperty())
                    || property.getAgg() == null || !StringUtils.hasText(property.getAlias())
                    || Arrays.asList("time", "_ts", "property").contains(property.getAlias())
                    || !aliases.add(property.getAlias())) {
                    throw new org.hswebframework.web.exception.BusinessException(
                        "聚合列、别名无效或别名重复", 400);
                }
            }
            propertyAlias = Arrays
                .stream(properties)
                .collect(Collectors.toMap(PropertyAggregation::getAlias,
                                          PropertyAggregation::getProperty));
            aliasToProperty = Arrays
                .stream(properties)
                .collect(Collectors.toMap(PropertyAggregation::getAlias,
                                          Function.identity()));
        }
    }
}
