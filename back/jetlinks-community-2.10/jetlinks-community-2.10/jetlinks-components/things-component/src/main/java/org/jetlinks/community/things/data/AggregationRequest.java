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
package org.jetlinks.community.things.data;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.community.Interval;
import org.jetlinks.community.doc.QueryConditionOnly;
import org.jetlinks.community.utils.ConverterUtils;
import org.jetlinks.reactor.ql.utils.CastUtils;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AggregationRequest {
    //时间间隔
    //为空时,不按时间分组
    @Schema(description = "聚合间隔，如1d；1w为自然周（周一至周日），1q为自然季度（1—3、4—6、7—9、10—12月）。"
        + "按Asia/Shanghai分桶，首尾不足周期仅统计from/to范围内的数据；返回时间为桶起点。", type = "string", defaultValue = "1d")
    @Nullable
    @Builder.Default
    Interval interval = Interval.ofDays(1);

    //时间格式
    @Schema(defaultValue = "时间格式,如:yyyy-MM-dd", description = "yyyy-MM-dd")
    @Builder.Default
    String format = "yyyy-MM-dd";

    @Schema(description = "时间从,如: 2020-09-01 00:00:00,支持表达式: now-1d")
    @Builder.Default
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = org.jetlinks.community.configuration.ShanghaiDateDeserializer.class)
    Date from = new DateTime(org.joda.time.DateTimeZone.forID("Asia/Shanghai"))
        .plusMonths(-1)
        .withHourOfDay(0)
        .withMinuteOfHour(0)
        .withSecondOfMinute(0)
        .toDate();

    @Schema(description = "时间到,如: 2020-09-30 00:00:00,支持表达式: now-1d")
    @Builder.Default
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = org.jetlinks.community.configuration.ShanghaiDateDeserializer.class)
    Date to = new DateTime(org.joda.time.DateTimeZone.forID("Asia/Shanghai"))
        .withHourOfDay(23)
        .withMinuteOfHour(59)
        .withSecondOfMinute(59)
        .toDate();

    @Schema(description = "数量限制")
    Integer limit;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Integer getExplicitLimit() {
        return limit;
    }

    //过滤条件
    @Schema(description = "过滤条件", implementation = QueryConditionOnly.class)
    @Builder.Default
    QueryParamEntity filter = QueryParamEntity.of();

    public int getLimit() {
        if (limit != null) {
            return limit;
        }
        if (interval == null) {
            return limit = 30;
        }
        long v = to.getTime() - from.getTime();

        limit = (int)Math.ceil(
            ((double) v / interval
                .getUnit()
                .getUnit()
                .getDuration()
                .toMillis())
        );

        return limit;
    }

    public AggregationRequest copy() {
        return new AggregationRequest(interval, format, from, to, limit,
                                      filter == null ? QueryParamEntity.of() : filter.clone());
    }

    @Hidden
    public void setQuery(QueryParamEntity filter) {
        setFilter(filter);
    }

    public void prepareTimestampCondition() {
        for (Term term : filter.getTerms()) {
            if ("timestamp".equals(term.getColumn())) {
                if (TermType.btw.equals(term.getTermType())) {
                    List<Object> values = ConverterUtils.convertToList(term.getValue());
                    if (!values.isEmpty()) {
                        from = CastUtils.castDate(values.get(0));
                    }
                    if (values.size() > 1) {
                        to = CastUtils.castDate(values.get(1));
                    }
                    term.setValue(null);
                } else if (TermType.gt.equals(term.getTermType()) || TermType.gte.equals(term.getTermType())) {
                    from = CastUtils.castDate(term.getValue());
                    term.setValue(null);
                } else if (TermType.lt.equals(term.getTermType()) || TermType.lte.equals(term.getTermType())) {
                    to = CastUtils.castDate(term.getValue());
                    term.setValue(null);
                }
            }
        }
    }
}
