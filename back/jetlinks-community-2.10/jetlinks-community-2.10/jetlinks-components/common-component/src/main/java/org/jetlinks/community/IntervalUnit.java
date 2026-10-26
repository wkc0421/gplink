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
package org.jetlinks.community;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 时间间隔单位，可用于计算时间范围的间隔周期
 *
 * @author zhouhao
 * @since 1.12
 */
@AllArgsConstructor
public enum IntervalUnit {
    MILLIS(1, ChronoUnit.MILLIS),
    SECONDS(1, ChronoUnit.SECONDS),
    MINUTES(1, ChronoUnit.MINUTES),
    HOURS(1, ChronoUnit.HOURS),
    DAYS(1, ChronoUnit.DAYS),
    WEEKS(1, ChronoUnit.WEEKS) {
        @Override
        protected LocalDateTime doTruncateTo(LocalDateTime time) {
            return time.truncatedTo(ChronoUnit.DAYS)
                       .minusDays(time.getDayOfWeek().getValue() - 1);
        }
    },
    MONTHS(1, ChronoUnit.MONTHS) {
        @Override
        protected LocalDateTime doTruncateTo(LocalDateTime time) {
            return time.truncatedTo(ChronoUnit.DAYS)
                       .withDayOfMonth(1);
        }
    },
    //季度
    QUARTER(3, ChronoUnit.MONTHS) {
        @Override
        protected LocalDateTime doTruncateTo(LocalDateTime time) {
            return time
                .withMonth(time.getMonth().firstMonthOfQuarter().getValue())
                .truncatedTo(ChronoUnit.DAYS)
                .withDayOfMonth(1);

        }
    },
    YEARS(1, ChronoUnit.YEARS) {
        @Override
        protected LocalDateTime doTruncateTo(LocalDateTime time) {
            return time.truncatedTo(ChronoUnit.DAYS)
                       .withDayOfYear(1);
        }
    },
    //不分区,永远返回0
    FOREVER(1, ChronoUnit.FOREVER) {
        @Override
        public long truncatedTo(long timestamp) {
            return 0;
        }

        @Override
        public Iterable<Long> iterate(long from, long to, int duration) {
            return () -> new Iterator<Long>() {
                private boolean nexted;

                @Override
                public boolean hasNext() {
                    return !nexted;
                }

                @Override
                public Long next() {
                    nexted = true;
                    return 0L;
                }
            };
        }
    };

    @Getter
    private final int durationOfUnit;

    @Getter
    private final ChronoUnit unit;

    protected LocalDateTime doTruncateTo(LocalDateTime time) {
        return time.truncatedTo(unit);
    }

    protected LocalDateTime next(LocalDateTime time, int duration) {
        return time.plus((long) durationOfUnit * duration, unit);
    }

    protected long next(long timestamp) {
        return next(timestamp, 1);
    }

    protected long next(long timestamp, int duration) {
        return this
            .next(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC), duration)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli();
    }

    public long truncatedTo(long timestamp) {
        return this
            .doTruncateTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli();
    }

    public final long truncatedTo(long timestamp, int duration) {
        return truncatedTo(timestamp, duration, ZoneOffset.UTC);
    }

    /** Same origins as time_bucket: Monday 2000-01-03, or 2000-01-01 for calendar months. */
    public final long truncatedTo(long timestamp, int duration, ZoneId zone) {
        if (duration <= 0) {
            throw new IllegalArgumentException("interval must be a positive integer");
        }
        if (this == FOREVER) {
            return 0;
        }
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zone);
        LocalDateTime origin;
        LocalDateTime bucket;
        if (this == MONTHS || this == QUARTER || this == YEARS) {
            origin = LocalDateTime.of(2000, 1, 1, 0, 0);
            long months = Math.multiplyExact((long) duration, this == YEARS ? 12 : durationOfUnit);
            long distance = ((long) time.getYear() - 2000) * 12 + time.getMonthValue() - 1;
            bucket = origin.plusMonths(Math.multiplyExact(Math.floorDiv(distance, months), months));
        } else {
            origin = LocalDateTime.of(2000, 1, 3, 0, 0);
            long width = toMillis(duration);
            long distance = Duration.between(origin, time).toMillis();
            bucket = origin.plus(Math.multiplyExact(Math.floorDiv(distance, width), width), ChronoUnit.MILLIS);
        }
        return bucket.atZone(zone).toInstant().toEpochMilli();
    }

    public final long advance(long timestamp, int duration, ZoneId zone) {
        return next(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zone), duration)
            .atZone(zone).toInstant().toEpochMilli();
    }

    public long toMillis(int duration) {
        return Math.multiplyExact(Math.multiplyExact((long) duration, durationOfUnit), unit.getDuration().toMillis());
    }

    public final Iterable<Long> iterate(long from, long to) {
        return iterate(from, to, 1);
    }

    /**
     * 迭代时间区间的每一个周期时间
     *
     * @param from     时间从
     * @param to       时间止
     * @param duration 间隔数量,比如 2天为一个间隔
     * @return 每个间隔的时间戳迭代器
     */
    public Iterable<Long> iterate(long from, long to, int duration) {
        if (duration <= 0 || from > to) {
            throw new IllegalArgumentException("invalid interval or time range");
        }
        return () -> new Iterator<Long>() {
            long _from = truncatedTo(from, duration);
            final long _to = truncatedTo(to, duration);
            boolean finished;

            @Override
            public boolean hasNext() {
                return !finished && _from <= _to;
            }

            @Override
            public Long next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                long that = _from;
                if (_from == _to) {
                    finished = true;
                } else {
                    _from = IntervalUnit.this.next(_from, duration);
                }
                return that;
            }
        };
    }

}
