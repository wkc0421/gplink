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
 * TimescaleDB 压缩策略表特性。
 * 建表时在 hypertable 上附加压缩配置与自动压缩调度策略。
 *
 * @see TimescaleDBCreateTableSqlBuilder
 */
@Getter
@AllArgsConstructor
public class CreateCompressionPolicy implements Feature, FeatureType {

    public static final FeatureId<CreateCompressionPolicy> ID = FeatureId.of("CreateCompressionPolicy");

    /** compress_segmentby 列（逗号分隔），影响压缩后的查询性能 */
    private final String segmentBy;

    /** compress_orderby 子句，例如 "timestamp DESC" */
    private final String orderBy;

    /** 超过此时间间隔的数据将被自动压缩 */
    private final Interval compressAfter;

    @Override
    public String getId() {
        return ID.getId();
    }

    @Override
    public String getName() {
        return "CreateCompressionPolicy";
    }

    @Override
    public FeatureType getType() {
        return this;
    }
}
