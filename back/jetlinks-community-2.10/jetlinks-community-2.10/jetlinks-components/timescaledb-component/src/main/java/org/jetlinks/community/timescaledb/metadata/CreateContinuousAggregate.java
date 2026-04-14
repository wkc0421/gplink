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
package org.jetlinks.community.timescaledb.metadata;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.ezorm.core.FeatureId;
import org.hswebframework.ezorm.core.FeatureType;
import org.hswebframework.ezorm.core.meta.Feature;
import org.jetlinks.community.Interval;

/**
 * TimescaleDB continuous aggregate 视图特性。
 * 建表时自动创建 _hourly_agg 物化视图及刷新策略。
 * 如果 dailyEnabled=true，还会在 _hourly_agg 之上创建 _daily_agg（层级 cagg）。
 * 如果 monthlyEnabled=true，会在 _daily_agg 之上继续创建 _monthly_agg。
 *
 * @see TimescaleDBCreateTableSqlBuilder
 */
@Getter
@AllArgsConstructor
public class CreateContinuousAggregate implements Feature, FeatureType {

    public static final FeatureId<CreateContinuousAggregate> ID =
        FeatureId.of("CreateContinuousAggregate");

    /** cagg 自动刷新间隔，例如 1h */
    private final Interval refreshInterval;

    /** 刷新策略 start_offset，例如 3h */
    private final Interval startOffset;

    /** 刷新策略 end_offset，例如 1h */
    private final Interval endOffset;

    /** cagg 视图数据保留时长，例如 3y */
    private final Interval retention;

    /** 是否在小时 cagg 之上建立日级层级 cagg */
    private final boolean dailyEnabled;

    /** 日级 cagg 刷新策略 start_offset，默认 25h */
    private final Interval dailyStartOffset;

    /** 是否在日级 cagg 之上建立月级层级 cagg（需要 dailyEnabled=true） */
    private final boolean monthlyEnabled;

    /** 物ID列名（实际 DB 列名，如 "deviceId" 或 "thingId"），用于生成 cagg 视图 SQL */
    private final String thingIdColumn;

    @Override
    public String getId() {
        return ID.getId();
    }

    @Override
    public String getName() {
        return "CreateContinuousAggregate";
    }

    @Override
    public FeatureType getType() {
        return this;
    }
}
