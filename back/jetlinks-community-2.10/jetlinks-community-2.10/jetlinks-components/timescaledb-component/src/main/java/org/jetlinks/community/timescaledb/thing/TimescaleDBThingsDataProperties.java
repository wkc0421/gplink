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
package org.jetlinks.community.timescaledb.thing;

import lombok.Getter;
import lombok.Setter;
import org.jetlinks.community.Interval;
import org.jetlinks.community.things.data.ThingsDataConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "timescaledb.things-data")
public class TimescaleDBThingsDataProperties {
    private boolean enabled = true;

    /**
     * 分区时间间隔
     */
    private Interval chunkTimeInterval = Interval.ofDays(7);

    /**
     * 数据保留时长
     */
    private Interval retentionPolicy = Interval.of(5, Interval.year);

    /**
     * 是否对属性表启用 TimescaleDB 列压缩（仅属性表）
     */
    private boolean compress = false;

    /**
     * 超过此时间间隔的数据自动压缩，默认 30 天
     */
    private Interval compressAfter = Interval.ofDays(30);

    /**
     * 压缩 segmentby 列（逗号分隔，直接写入 SQL），与实际 DB 列名对齐
     */
    private String compressSegmentBy = "\"" + ThingsDataConstants.COLUMN_THING_ID + "\"," + ThingsDataConstants.COLUMN_PROPERTY_ID;

    /**
     * 压缩 orderby 子句
     */
    private String compressOrderBy = "timestamp DESC";

    /**
     * 小时级 continuous aggregate 配置（仅属性表）
     */
    private Cagg cagg = new Cagg();

    /** Product/property-specific cumulative counter boundary snapshots. */
    private CounterSnapshot counterSnapshot = new CounterSnapshot();

    /** Resource bounds apply before SQL execution and gap filling. */
    private AggregationLimits aggregation = new AggregationLimits();

    @Getter
    @Setter
    public static class AggregationLimits {
        private int maxBuckets = 10_000;
        private int maxColumns = 100;
        private int maxCells = 100_000;
        private java.time.Duration timeout = java.time.Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class CounterSnapshot {
        /** Enables infrastructure only; no product/property is opted in by default. */
        private boolean enabled = true;
        private Interval hourChunkInterval = Interval.ofDays(30);
        private Interval dayChunkInterval = Interval.ofDays(365);
        private Interval retention = Interval.of(5, Interval.year);
        private java.time.Duration pollInterval = java.time.Duration.ofMinutes(5);
        private java.time.Duration boundaryDelay = java.time.Duration.ofMinutes(5);
        private int batchDevices = 50;
        private int batchBoundaries = 24;
        private int concurrency = 2;
        private java.time.Duration sqlTimeout = java.time.Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Cagg {
        /**
         * 是否建立小时级 cagg 物化视图，默认 false
         */
        private boolean enabled = false;

        /**
         * cagg 自动刷新间隔，默认 1 小时
         */
        private Interval refreshInterval = Interval.ofHours(1);

        /**
         * 兼容旧配置；实际刷新起点由原始数据 retention-policy 决定。
         */
        private Interval startOffset = Interval.of(5, Interval.year);

        /**
         * 刷新策略 end_offset，默认 1 小时
         */
        private Interval endOffset = Interval.ofHours(1);

        /**
         * cagg 视图数据保留时长，默认 3 年
         */
        private Interval retention = Interval.of(5, Interval.year);

        /**
         * 是否在小时 cagg 之上建立日级层级 cagg（_daily_agg），默认 false
         */
        private boolean dailyEnabled = false;

        /**
         * 兼容旧配置；日/月级同样使用原始数据 retention-policy 作为回看窗口。
         */
        private Interval dailyStartOffset = Interval.of(5, Interval.year);

        /**
         * 是否在日级 cagg 之上建立月级层级 cagg（_monthly_agg），需要 dailyEnabled=true，默认 false
         */
        private boolean monthlyEnabled = false;
    }
}
