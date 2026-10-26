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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Values of one property at ordered time boundaries.
 *
 * <p>{@code initialValue} is the last non-null value at or before
 * {@code startTime}. If no such value exists, it is the first non-null value
 * inside {@code [startTime, endTime]}. Intermediate boundaries carry the last
 * non-null reading forward. The outer end uses the last in-range reading, or,
 * when the range has none, the first non-null observed reading after {@code endTime}, up to now.
 * An unavailable end remains null; a start fallback is never used as its substitute.</p>
 */
public final class PropertyBoundaryValues {

    private final String property;
    private final long startTime;
    private final long endTime;
    private final Object initialValue;
    private final List<BoundaryValue> values;

    public PropertyBoundaryValues(String property,
                                  long startTime,
                                  long endTime,
                                  @Nullable Object initialValue,
                                  List<BoundaryValue> values) {
        this.property = property;
        this.startTime = startTime;
        this.endTime = endTime;
        this.initialValue = initialValue;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public String getProperty() {
        return property;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    @Nullable
    public Object getInitialValue() {
        return initialValue;
    }

    public List<BoundaryValue> getValues() {
        return values;
    }

    public static PropertyBoundaryValues empty(String property,
                                               long startTime,
                                               long endTime,
                                               List<Long> boundaries) {
        List<BoundaryValue> values = new ArrayList<>(boundaries.size());
        for (Long boundary : boundaries) {
            values.add(new BoundaryValue(boundary, null));
        }
        return new PropertyBoundaryValues(property, startTime, endTime, null, values);
    }

    public static final class BoundaryValue {
        private final long boundary;
        private final Object value;

        public BoundaryValue(long boundary, @Nullable Object value) {
            this.boundary = boundary;
            this.value = value;
        }

        public long getBoundary() {
            return boundary;
        }

        @Nullable
        public Object getValue() {
            return value;
        }
    }
}
