package org.jetlinks.community.timescaledb.thing.simulation;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Persisted, immutable-by-convention inputs. Values never pass through JavaScript doubles. */
public class SimulationSpec {
    public String deviceId, propertyId, requestId, mode;
    public long startTime, endTime;
    public int intervalMinutes;
    public String min, max, initial, increment;
    public boolean snapshots;
    // Resolved exclusively by the server.
    public String productId, rawTable, type, name, unit, lower, upper;
    public int scale;
    public boolean snapshotEligible;
    public boolean allowEnableSnapshots;
    public long seed;
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public boolean sameRequest(SimulationSpec other) {
        return Objects.equals(deviceId,other.deviceId)&&Objects.equals(propertyId,other.propertyId)&&startTime==other.startTime&&endTime==other.endTime
            &&intervalMinutes==other.intervalMinutes&&snapshots==other.snapshots&&Objects.equals(mode,other.mode)
            &&Objects.equals(min,other.min)&&Objects.equals(max,other.max)&&Objects.equals(initial,other.initial)&&Objects.equals(increment,other.increment);
    }

    public long count() { return (endTime - startTime - 1) / step() + 1; }
    public long step() { return intervalMinutes * 60_000L; }
    public long time(long index) { return Math.addExact(startTime, Math.multiplyExact(index, step())); }
    public static LocalDateTime local(long time) { return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDateTime(); }

    public void validate(long now, long oldest) {
        require(deviceId != null && !deviceId.isBlank() && propertyId != null && !propertyId.isBlank(), "请选择设备和采集项");
        require(startTime > 0 && endTime > startTime && endTime <= now, "时间范围无效或包含未来时间");
        require(!local(endTime).isAfter(local(startTime).plusYears(2)), "时间跨度不能超过两个日历年");
        require(startTime >= oldest, "开始时间超出数据库实际保留范围");
        require(Set.of(5, 15, 30, 60).contains(intervalMinutes), "采样间隔仅支持5、15、30、60分钟");
        require(scale >= 0 && scale <= 18, "采集项精度必须在0至18位之间");
        require(!snapshots || snapshotEligible, "该采集项不能启用设备累计量快照");
        require(Set.of("RANDOM", "INCREMENT").contains(mode == null ? "" : mode), "请选择生成方式");
        if ("RANDOM".equals(mode)) {
            BigDecimal a = decimal(min), b = decimal(max);
            require(a.compareTo(b) <= 0, "最小值不能超过最大值"); check(a); check(b);
            require(b.unscaledValue().subtract(a.unscaledValue()).bitLength() <= 255,"随机数范围过大");
        } else {
            BigDecimal a = decimal(initial), delta = decimal(increment);
            require(delta.signum() > 0, "递增值必须大于0");
            check(a); check(a.add(delta.multiply(BigDecimal.valueOf(count() - 1))));
        }
    }

    public BigDecimal decimal(String text) {
        try {
            require(text != null && text.length() <= 128 && text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"), "请输入有限十进制数值");
            return new BigDecimal(text).setScale(scale, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("数值格式或精度不符合采集项要求", e);
        }
    }

    private void check(BigDecimal value) {
        require(Double.isFinite(value.doubleValue()), "数值超出存储范围");
        require(BigDecimal.valueOf(value.doubleValue()).compareTo(value)==0
            && BigDecimal.valueOf(Math.ulp(value.doubleValue())).compareTo(BigDecimal.ONE.scaleByPowerOfTen(-scale))<=0,
            "数值在现有numberValue双精度存储中会丢失声明精度");
        if ("float".equals(type)) {
            require(Float.isFinite(value.floatValue()), "数值超出float范围");
            require(new BigDecimal(Float.toString(value.floatValue())).compareTo(value)==0
                && BigDecimal.valueOf(Math.ulp(value.floatValue())).compareTo(BigDecimal.ONE.scaleByPowerOfTen(-scale))<=0,
                "数值在float类型中会丢失声明精度");
        }
        if (lower != null) require(value.compareTo(new BigDecimal(lower)) >= 0, "数值低于采集项下限");
        if (upper != null) require(value.compareTo(new BigDecimal(upper)) <= 0, "数值超过采集项上限");
        if ("int".equals(type)) { require(value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0 && value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0, "数值超出int范围"); }
        if ("long".equals(type)) { require(value.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0 && value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0, "数值超出long范围"); }
    }

    public BigDecimal value(long index) {
        if ("INCREMENT".equals(mode)) return decimal(initial).add(decimal(increment).multiply(BigDecimal.valueOf(index)));
        BigInteger lo = decimal(min).unscaledValue(), width = decimal(max).unscaledValue().subtract(lo).add(BigInteger.ONE);
        // Rejection sampling over a fixed-width hash: uniform, inclusive, independently reproducible per index.
        BigInteger space = BigInteger.ONE.shiftLeft(256), limit = space.subtract(space.mod(width));
        require(width.compareTo(space) <= 0, "随机数范围过大");
        try {
            for (long attempt = 0; ; attempt++) {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest((seed + ":" + index + ":" + attempt).getBytes(StandardCharsets.UTF_8));
                BigInteger n = new BigInteger(1, hash);
                if (n.compareTo(limit) < 0) return new BigDecimal(lo.add(n.mod(width)), scale);
            }
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String rowId(String taskId, long index) {
        return UUID.nameUUIDFromBytes((taskId + ":" + index).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
