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
    private Interval retentionPolicy;

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
    private String compressSegmentBy = "thing_id,property";

    /**
     * 压缩 orderby 子句
     */
    private String compressOrderBy = "timestamp DESC";
}
