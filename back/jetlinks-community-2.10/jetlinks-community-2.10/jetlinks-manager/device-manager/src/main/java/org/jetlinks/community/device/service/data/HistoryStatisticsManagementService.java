package org.jetlinks.community.device.service.data;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.jetlinks.community.timescaledb.thing.counter.*;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.metadata.UnitSupported;
import org.jetlinks.core.metadata.types.NumberType;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/** Cross-database management facade. Pagination is performed by the counter store, names stay in the main store. */
@Service
public class HistoryStatisticsManagementService {
    private final LocalDeviceProductService products;
    private final LocalDeviceInstanceService devices;
    private final DeviceDataStorageProperties storage;
    private final ObjectProvider<CounterSnapshotService> snapshots;
    private final HistoryStatisticsMetadataService metadata;

    public HistoryStatisticsManagementService(LocalDeviceProductService products, LocalDeviceInstanceService devices,
                                              DeviceDataStorageProperties storage, ObjectProvider<CounterSnapshotService> snapshots,
                                              HistoryStatisticsMetadataService metadata) {
        this.products = products;
        this.devices = devices;
        this.storage = storage;
        this.snapshots = snapshots;
        this.metadata = metadata;
    }

    public CounterSnapshotService counter() {
        CounterSnapshotService service = snapshots.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "历史统计服务不可用");
        return service;
    }

    protected Mono<Authentication> authentication() { return Authentication.currentReactive(); }

    public Mono<Authentication> authorize(String scope, String action) {
        if (!Set.of("PRODUCT", "DEVICE").contains(scope == null ? "" : scope)) {
            return Mono.error(badRequest("scope 必须为 PRODUCT 或 DEVICE"));
        }
        String resource = "PRODUCT".equals(scope) ? "device-product" : "device-instance";
        return authentication()
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
            .flatMap(auth -> auth.hasPermission(resource, action) ? Mono.just(auth)
                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "没有" + resource + "." + action + "权限")));
    }

    public Mono<Page<Row>> query(Query request) {
        return authorize(request == null ? null : request.getScope(), "query").then(Mono.defer(() -> {
            validatePage(request.getPageIndex(), request.getPageSize());
            validateIds(request.getProductIds()); validateIds(request.getDeviceIds()); validateIds(request.getPropertyIds());
            if (request.getSource() != null && !Set.of("INHERIT", "ENABLE", "DISABLE", "OVERRIDE").contains(request.getSource())) {
                return Mono.error(badRequest("无效配置来源"));
            }
            // Empty UI selections mean no filter. Keep this normalization at the public boundary:
            // an internally resolved empty set must still mean no permitted/current results.
            Query filters = copy(request);
            filters.setProductIds(emptyFilterAsNull(request.getProductIds()));
            filters.setDeviceIds(emptyFilterAsNull(request.getDeviceIds()));
            filters.setPropertyIds(emptyFilterAsNull(request.getPropertyIds()));
            return currentProductIds(filters.getProductIds()).flatMap(ids -> {
                if (ids.isEmpty()) return Mono.just(new Page<Row>(List.of(), 0, request.getPageIndex(), request.getPageSize()));
                Query query = copy(filters); query.setProductIds(ids);
                return metadata.awaitInitialized().then(StringUtils.hasText(query.getKeyword())
                    ? keywordQuery(query) : directQuery(query).flatMap(page -> page.getData().size() < Math.min(query.getPageSize(),
                        Math.max(0L, page.getTotal() - (long) query.getPageIndex() * query.getPageSize())) ? keywordQuery(query) : Mono.just(page)));
            });
        }));
    }

    private Mono<List<String>> currentProductIds(List<String> requested) {
        Flux<String> ids = requested == null || requested.isEmpty() ? counter().configuredProductIds() : Flux.fromIterable(requested);
        return ids.distinct().buffer(200).concatMap(batch -> products.createQuery()
            .select(DeviceProductEntity::getId).in(DeviceProductEntity::getId, batch).fetch()
            .map(DeviceProductEntity::getId)).collectList();
    }

    private Mono<Page<Row>> directQuery(Query request) {
        return counter().queryPolicies(request).flatMap(page -> enrich(page.getData(), request.getScope())
            .flatMap(rows -> {
                return Mono.just(new Page<>(rows, page.getTotal(), request.getPageIndex(), request.getPageSize()));
            }));
    }

    /** Names cannot be joined across separate databases. Scan bounded control-table pages, retaining only the requested result page. */
    private Mono<Page<Row>> keywordQuery(Query request) {
        Query scan = copy(request); scan.setPageIndex(0); scan.setPageSize(200);
        String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim().toLowerCase(Locale.ROOT);
        long offset = (long) request.getPageIndex() * request.getPageSize();
        return directQuery(scan).expand(page -> {
            long consumed = ((long) page.getPageIndex() + 1) * page.getPageSize();
            if (consumed >= page.getTotal()) return Mono.empty();
            Query next = copy(scan); next.setPageIndex(page.getPageIndex() + 1);
            return directQuery(next);
        }).flatMapIterable(Page::getData)
            .filter(row -> matches(row, keyword))
            .collect(() -> new MatchPage(offset, request.getPageSize()), MatchPage::add)
            .map(result -> new Page<>(result.rows, result.total, request.getPageIndex(), request.getPageSize()));
    }

    private Mono<List<Row>> enrich(List<CounterPolicyRow> rows, String scope) {
        if (rows == null || rows.isEmpty()) return Mono.just(List.of());
        List<String> productIds = rows.stream().map(CounterPolicyRow::getProductId).distinct().collect(Collectors.toList());
        List<String> deviceIds = rows.stream().map(CounterPolicyRow::getDeviceId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Mono<Map<String, DeviceProductEntity>> productMap = products.createQuery().in(DeviceProductEntity::getId, productIds)
            .fetch().collectMap(DeviceProductEntity::getId);
        Mono<Map<String, DeviceInstanceEntity>> deviceMap = deviceIds.isEmpty() ? Mono.just(Map.of())
            : devices.createQuery().in(DeviceInstanceEntity::getId, deviceIds).fetch().collectMap(DeviceInstanceEntity::getId);
        return Mono.zip(productMap, deviceMap).map(tuple -> {
            List<Row> result = new ArrayList<>();
            for (CounterPolicyRow policy : rows) {
                DeviceProductEntity product = tuple.getT1().get(policy.getProductId());
                DeviceInstanceEntity device = policy.getDeviceId() == null ? null : tuple.getT2().get(policy.getDeviceId());
                if (product == null || (policy.getDeviceId() != null && device == null)) continue;
                Row row = describe(scope, product, device, policy.getPropertyId());
                boolean currentlyEligible = row.isEligible();
                BeanUtils.copyProperties(policy, row);
                // Eligibility describes the current model, including a model edited after roster reconciliation.
                row.setEligible(currentlyEligible && row.isEligible());
                if (!currentlyEligible) {
                    row.setEnabled(false);
                    if (row.getConfig() != null) { row.getConfig().setEnabled(false); row.getConfig().setStatus("SUSPENDED"); }
                }
                result.add(row);
            }
            return result;
        });
    }

    private static Row describe(String scope, DeviceProductEntity product, DeviceInstanceEntity device, String propertyId) {
        Row row = new Row(); row.setScope(scope); row.setProductId(product.getId()); row.setProductName(product.getName());
        if (device != null) { row.setDeviceId(device.getId()); row.setDeviceName(device.getName()); }
        row.setPropertyId(propertyId); row.setPropertyName(propertyId);
        DeviceMetadata productModel = HistoryStatisticsMetadataService.productMetadata(product);
        DeviceMetadata effective = device == null ? productModel : HistoryStatisticsMetadataService.effectiveMetadata(product, device);
        PropertyMetadata property = effective.getPropertyOrNull(propertyId);
        row.setPropertySource(productModel.getPropertyOrNull(propertyId) == null ? "DEVICE" : "PRODUCT");
        row.setEligible(property != null && property.getValueType() instanceof NumberType);
        if (property != null) {
            row.setPropertyName(StringUtils.hasText(property.getName()) ? property.getName() : propertyId);
            row.setValueType(property.getValueType().getType());
            if (property.getValueType() instanceof UnitSupported) {
                UnitSupported unit = (UnitSupported) property.getValueType();
                if (unit.getUnit() != null) row.setUnit(unit.getUnit().getSymbol());
            }
        }
        return row;
    }

    public Mono<Page<Row>> candidates(CandidateQuery request) {
        return authorize(request == null ? null : request.getScope(), "query").then(Mono.defer(() -> {
            validatePage(request.getPageIndex(), request.getPageSize());
            List<String> targetIds = "PRODUCT".equals(request.getScope()) ? request.getProductIds() : request.getDeviceIds();
            validateIds(targetIds);
            if (targetIds == null || targetIds.isEmpty()) return Mono.error(badRequest("请先选择产品或设备"));
            Flux<Row> candidates = Flux.fromIterable(new LinkedHashSet<>(targetIds)).concatMap(id ->
                "PRODUCT".equals(request.getScope()) ? metadata.product(id).flatMapMany(product -> candidateRows(request, product, null))
                    : metadata.device(id).flatMapMany(device -> metadata.product(device.getProductId())
                        .flatMapMany(product -> candidateRows(request, product, device))));
            String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim().toLowerCase(Locale.ROOT);
            long offset = (long) request.getPageIndex() * request.getPageSize();
            return metadata.awaitInitialized().thenMany(candidates).filter(row -> matches(row, keyword))
                .collect(() -> new MatchPage(offset, request.getPageSize()), MatchPage::add)
                .map(result -> new Page<>(result.rows, result.total, request.getPageIndex(), request.getPageSize()));
        }));
    }

    private Flux<Row> candidateRows(CandidateQuery request, DeviceProductEntity product, DeviceInstanceEntity device) {
        DeviceMetadata model = device == null ? HistoryStatisticsMetadataService.productMetadata(product)
            : HistoryStatisticsMetadataService.effectiveMetadata(product, device);
        CounterPolicyQuery policies = new CounterPolicyQuery(); policies.setScope(request.getScope());
        policies.setProductIds(List.of(product.getId())); policies.setPageSize(200);
        if (device != null) policies.setDeviceIds(List.of(device.getId()));
        return counter().queryPolicies(policies).expand(page -> {
            if (((long) page.getPageIndex() + 1) * page.getPageSize() >= page.getTotal()) return Mono.empty();
            CounterPolicyQuery next = new CounterPolicyQuery(); BeanUtils.copyProperties(policies, next);
            next.setPageIndex(page.getPageIndex() + 1); return counter().queryPolicies(next);
        }).flatMapIterable(CounterPolicyPage::getData).collectMap(CounterPolicyRow::getPropertyId)
            .flatMapMany(configured -> Flux.fromIterable(model.getProperties())
                .filter(property -> property.getValueType() instanceof NumberType)
                .sort(Comparator.comparing(PropertyMetadata::getId))
                .map(property -> {
                    Row row = describe(request.getScope(), product, device, property.getId());
                    CounterPolicyRow policy = configured.get(property.getId());
                    if (policy != null) BeanUtils.copyProperties(policy, row);
                    else { row.setMode(device == null ? "DISABLE" : "INHERIT"); row.setEnabled(false); }
                    row.setEligible(true);
                    return row;
                }));
    }

    public Mono<BatchResult> batch(BatchRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty() || request.getItems().size() > 500
            || request.getItems().stream().anyMatch(Objects::isNull)) {
            return Mono.error(badRequest("每次操作需要 1 到 500 个采集项"));
        }
        // Check every requested scope before changing anything; each item still validates its own target and model.
        return Flux.fromIterable(request.getItems()).map(item -> item.getScope() == null ? "" : item.getScope()).distinct()
            .concatMap(scope -> authorize(scope, "save")).thenMany(Flux.fromIterable(request.getItems())
                .concatMap(item -> apply(item).map(changed -> BatchItemResult.success(item, changed))
                    .onErrorResume(error -> Mono.just(BatchItemResult.failure(item, message(error))))))
            .collectList().map(BatchResult::new);
    }

    private Mono<Boolean> apply(BatchItem item) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(item.getPropertyId())) return Mono.error(badRequest("采集项标识不能为空"));
            if (!Set.of("ENABLE", "DISABLE", "INHERIT").contains(item.getMode() == null ? "" : item.getMode())) {
                return Mono.error(badRequest("无效操作模式"));
            }
            if ("PRODUCT".equals(item.getScope())) {
                if ("INHERIT".equals(item.getMode())) return Mono.error(badRequest("产品默认不支持恢复继承"));
                return metadata.product(item.getProductId()).flatMap(product -> {
                    boolean enabled = "ENABLE".equals(item.getMode());
                    Mono<Void> validation = enabled ? requireNumeric(HistoryStatisticsMetadataService.productMetadata(product), item.getPropertyId())
                        : counter().list(product.getId()).any(config -> item.getPropertyId().equals(config.getPropertyId()))
                            .flatMap(exists -> exists ? Mono.empty() : requireNumeric(HistoryStatisticsMetadataService.productMetadata(product), item.getPropertyId()));
                    return validation.then(policy("PRODUCT", product.getId(), null, item.getPropertyId()))
                        .flatMap(current -> {
                            if (item.getMode().equals(current.getMode())) {
                                PropertyMetadata property = HistoryStatisticsMetadataService.productMetadata(product).getPropertyOrNull(item.getPropertyId());
                                boolean eligible = property != null && property.getValueType() instanceof NumberType;
                                return (current.getConfig() != null && current.isEligible() != eligible
                                    ? metadata.syncProduct(product.getId()) : Mono.<Void>empty()).thenReturn(false);
                            }
                            return authentication().flatMap(auth -> counter().upsert(product.getId(),
                                item.getPropertyId(), enabled, rawTable(product.getId()), auth.getUser().getId()))
                                .then(metadata.syncProduct(product.getId())).thenReturn(true);
                        });
                });
            }
            return metadata.device(item.getDeviceId()).flatMap(device -> {
                if (StringUtils.hasText(item.getProductId()) && !device.getProductId().equals(item.getProductId())) {
                    return Mono.error(badRequest("设备不属于指定产品"));
                }
                item.setProductId(device.getProductId());
                return metadata.product(device.getProductId()).flatMap(product -> {
                    DeviceMetadata model = HistoryStatisticsMetadataService.effectiveMetadata(product, device);
                    Mono<Void> validation = "ENABLE".equals(item.getMode()) ? requireNumeric(model, item.getPropertyId()) : Mono.empty();
                    Boolean enabled = "INHERIT".equals(item.getMode()) ? null : "ENABLE".equals(item.getMode());
                    return validation.then(policy("DEVICE", product.getId(), device.getId(), item.getPropertyId()))
                        .flatMap(current -> {
                            PropertyMetadata property = model.getPropertyOrNull(item.getPropertyId());
                            boolean eligible = property != null && property.getValueType() instanceof NumberType;
                            // An already absent invalid item has nothing to stop; do not create a phantom rule.
                            if (current.getConfig() == null && !eligible && !"ENABLE".equals(item.getMode())) return Mono.just(false);
                            if (item.getMode().equals(current.getMode())) {
                                return (current.getConfig() != null && current.isEligible() != eligible
                                    ? metadata.syncDevice(device.getId()) : Mono.<Void>empty()).thenReturn(false);
                            }
                            return metadata.syncDevice(device.getId()).then(authentication()).flatMap(auth -> counter().upsertDevice(product.getId(),
                                item.getPropertyId(), device.getId(), enabled, rawTable(product.getId()), auth.getUser().getId()))
                                .then(metadata.syncDevice(device.getId())).thenReturn(true);
                        });
                });
            });
        });
    }

    private Mono<CounterPolicyRow> policy(String scope, String product, String device, String property) {
        CounterPolicyQuery query = new CounterPolicyQuery(); query.setScope(scope); query.setProductIds(List.of(product));
        query.setPropertyIds(List.of(property)); query.setPageSize(1);
        if (device != null) query.setDeviceIds(List.of(device));
        CounterPolicyRow missing = new CounterPolicyRow(); missing.setMode(device == null ? "DISABLE" : "INHERIT");
        return counter().queryPolicies(query).flatMap(page -> Mono.justOrEmpty(page.getData().stream().findFirst())).defaultIfEmpty(missing);
    }

    public Mono<Map<String, String>> rebuildDevice(String deviceId, String propertyId, Long from, Long to) {
        return authorize("DEVICE", "save").flatMap(auth -> {
            validateRange(from, to);
            return metadata.device(deviceId).flatMap(device -> metadata.effectiveMetadata(deviceId)
                .flatMap(model -> requireNumeric(model, propertyId))
                .then(metadata.syncDevice(deviceId))
                .then(counter().rebuildDevice(device.getProductId(), propertyId, deviceId, from, to, auth.getUser().getId())))
                .onErrorMap(IllegalArgumentException.class, error -> badRequest(error.getMessage()))
                .map(id -> Map.of("taskId", id));
        });
    }

    public Mono<CounterTask> deviceTask(String deviceId, String taskId, boolean cancel) {
        return authorize("DEVICE", cancel ? "save" : "query").then(metadata.device(deviceId))
            .flatMap(device -> counter().task(taskId)
                .filter(task -> deviceId.equals(task.getDeviceId()) && device.getProductId().equals(task.getProductId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "设备任务不存在"))))
            .flatMap(task -> cancel ? counter().cancel(task.getId()) : Mono.just(task));
    }

    private String rawTable(String productId) {
        return TimescaleDBUtils.getTableName(storage.getMetric().getPropertyPrefix() + productId);
    }

    private static Mono<Void> requireNumeric(DeviceMetadata model, String id) {
        PropertyMetadata property = model.getPropertyOrNull(id);
        return property != null && property.getValueType() instanceof NumberType ? Mono.empty()
            : Mono.error(badRequest("采集项必须存在于当前物模型且为数值类型"));
    }

    public static void validateRange(Long from, Long to) {
        long oldest = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusYears(5)
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        if (from == null || to == null || from >= to || from < oldest || to > System.currentTimeMillis()) {
            throw badRequest("回填时间必须先后有序、位于最近五年且不晚于当前时间");
        }
    }

    private static void validatePage(int index, int size) {
        if (index < 0 || size < 1 || size > 200) throw badRequest("分页大小必须为 1 到 200，页码从 0 开始");
    }
    private static void validateIds(List<String> ids) {
        if (ids != null && (ids.size() > 500 || ids.stream().anyMatch(id -> !StringUtils.hasText(id)))) {
            throw badRequest("每次最多选择 500 个有效标识");
        }
    }
    private static boolean matches(Row row, String keyword) {
        return keyword.isEmpty() || row.getPropertyId().toLowerCase(Locale.ROOT).contains(keyword)
            || (row.getPropertyName() != null && row.getPropertyName().toLowerCase(Locale.ROOT).contains(keyword));
    }
    private static Query copy(Query value) { Query copy = new Query(); BeanUtils.copyProperties(value, copy); return copy; }
    private static List<String> emptyFilterAsNull(List<String> ids) { return ids != null && ids.isEmpty() ? null : ids; }
    private static String message(Throwable error) {
        return error instanceof ResponseStatusException ? ((ResponseStatusException) error).getReason()
            : error.getMessage() == null ? "操作失败" : error.getMessage();
    }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }

    @Data @EqualsAndHashCode(callSuper = true)
    public static class Query extends CounterPolicyQuery { private String keyword; }
    @Data
    public static class CandidateQuery {
        private String scope; private List<String> productIds; private List<String> deviceIds;
        private String keyword; private int pageIndex; private int pageSize = 50;
    }
    @Data @EqualsAndHashCode(callSuper = true)
    public static class Row extends CounterPolicyRow {
        private String scope; private String productName; private String deviceName;
        private String propertyName; private String propertySource; private String valueType; private String unit;
    }
    @Data
    public static class Page<T> {
        private final List<T> data; private final long total; private final int pageIndex; private final int pageSize;
    }
    @Data
    public static class BatchItem {
        private String scope; private String productId; private String deviceId; private String propertyId; private String mode;
    }
    @Data
    public static class BatchRequest { private List<BatchItem> items; }
    @Data @EqualsAndHashCode(callSuper = true)
    public static class BatchItemResult extends BatchItem {
        private boolean success; private boolean changed; private String message;
        static BatchItemResult success(BatchItem item, boolean changed) { BatchItemResult result = result(item); result.setSuccess(true); result.setChanged(changed); return result; }
        static BatchItemResult failure(BatchItem item, String message) { BatchItemResult result = result(item); result.setMessage(message); return result; }
        private static BatchItemResult result(BatchItem item) { BatchItemResult result = new BatchItemResult(); BeanUtils.copyProperties(item, result); return result; }
    }
    @Data
    public static class BatchResult {
        private final int total; private final long succeeded; private final long failed; private final List<BatchItemResult> items;
        BatchResult(List<BatchItemResult> items) { this.items = items; total = items.size(); succeeded = items.stream().filter(BatchItemResult::isSuccess).count(); failed = total - succeeded; }
    }
    private static class MatchPage {
        final long offset; final int size; long total; final List<Row> rows = new ArrayList<>();
        MatchPage(long offset, int size) { this.offset = offset; this.size = size; }
        void add(Row row) { if (total >= offset && rows.size() < size) rows.add(row); total++; }
    }
}
