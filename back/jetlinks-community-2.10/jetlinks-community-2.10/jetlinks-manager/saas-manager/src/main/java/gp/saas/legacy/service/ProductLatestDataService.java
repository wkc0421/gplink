package gp.saas.legacy.service;

import gp.saas.legacy.dto.ProductLatestData;
import gp.saas.legacy.dto.ProductLatestData.Column;
import gp.saas.legacy.dto.ProductLatestData.Property;
import gp.saas.legacy.dto.ProductLatestData.Row;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.Permission;
import org.hswebframework.web.authorization.User;
import org.hswebframework.web.authorization.access.DataAccessConfig;
import org.hswebframework.web.authorization.access.OwnCreatedDataAccessConfig;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.web.DeviceInstanceController;
import org.jetlinks.community.device.web.DeviceProductController;
import org.jetlinks.core.metadata.DeviceMetadata;
import org.jetlinks.supports.official.JetLinksDeviceMetadataCodec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProductLatestDataService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_BATCH_SIZE = 100;
    private static final int DEVICE_CONCURRENCY = 4;
    private static final String PRODUCT_PERMISSION = "device-product";
    private static final String DEVICE_PERMISSION = "device-instance";
    private static final Set<String> DATA_PERMISSIONS = Set.of(PRODUCT_PERMISSION, DEVICE_PERMISSION);
    private static final String ROLE_DIMENSION = "role";
    private static final byte ACTIVE_SETTING = 1;

    private final DeviceProductController products;
    private final DeviceInstanceController devices;
    private final LegacyLatestPropertyQuery latestProperties;
    private final ReactiveRepository<AuthorizationSettingEntity, String> roleSettings;

    public ProductLatestDataService(DeviceProductController products,
                                    DeviceInstanceController devices,
                                    LegacyLatestPropertyQuery latestProperties,
                                    ReactiveRepository<AuthorizationSettingEntity, String> roleSettings) {
        this.products = products;
        this.devices = devices;
        this.latestProperties = latestProperties;
        this.roleSettings = roleSettings;
    }

    public Mono<ProductLatestData> query(String productId, int pageIndex, int pageSize) {
        if (pageIndex < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE
            || (long) pageIndex * pageSize > Integer.MAX_VALUE) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的分页参数"));
        }
        return queryScope().flatMap(scope -> product(productId, scope.productCreator())
            .flatMap(product -> devices
                .queryPager(Mono.just(deviceQuery(productId, scope.deviceCreator(), pageIndex, pageSize, null)))
                .flatMap(page -> rows(Flux.fromIterable(page.getData()), product)
                    .collectList()
                    .map(data -> new ProductLatestData(product.columns(), page.getTotal(), data)))));
    }

    public Mono<Export> export(String productId) {
        return queryScope().flatMap(scope -> product(productId, scope.productCreator())
            .map(product -> {
                Flux<DeviceInstanceEntity> roster = exportBatch(productId, scope.deviceCreator(), null)
                    .expand(batch -> batch.size() < EXPORT_BATCH_SIZE
                        ? Mono.empty()
                        : exportBatch(productId, scope.deviceCreator(), batch.get(batch.size() - 1).getId()))
                    .concatMapIterable(batch -> batch, 1);
                return new Export(product.columns(), rows(roster, product));
            }));
    }

    protected Mono<Authentication> authentication() {
        return Authentication.currentReactive();
    }

    private Mono<QueryScope> queryScope() {
        return authentication()
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
            .flatMap(auth -> {
                Set<String> restricted = new HashSet<>();
                Set<String> unresolved = new HashSet<>(DATA_PERMISSIONS);
                for (String permission : DATA_PERMISSIONS) {
                    if (hasEffectiveOwnerScope(auth, permission)) {
                        restricted.add(permission);
                        unresolved.remove(permission);
                    }
                }
                return storedOwnerScopes(auth, unresolved)
                    .doOnNext(restricted::add)
                    .then(Mono.fromSupplier(() -> new QueryScope(
                        creatorScope(auth, restricted.contains(PRODUCT_PERMISSION)),
                        creatorScope(auth, restricted.contains(DEVICE_PERMISSION)))));
            });
    }

    private static boolean hasEffectiveOwnerScope(Authentication auth, String permissionId) {
        return auth.getPermission(permissionId).stream()
            .flatMap(permission -> permission.getDataAccesses(Permission.ACTION_QUERY).stream())
            .anyMatch(OwnCreatedDataAccessConfig.class::isInstance);
    }

    private Flux<String> storedOwnerScopes(Authentication auth, Set<String> permissions) {
        if (permissions.isEmpty()) {
            return Flux.empty();
        }
        Set<String> roleIds = auth.getDimensions(ROLE_DIMENSION).stream()
            .map(Dimension::getId)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
        if (roleIds.isEmpty()) {
            return Flux.empty();
        }
        // Community leaves role settings out of the authentication object. These rows
        // can only restrict results. The console controller still authorizes both queries.
        QueryParamEntity query = QueryParamEntity.of("dimensionType", ROLE_DIMENSION).noPaging();
        query.addTerm(Term.of("state", TermType.eq, ACTIVE_SETTING));
        query.addTerm(Term.of("dimensionTarget", TermType.in, roleIds));
        query.addTerm(Term.of("permission", TermType.in, permissions));
        return roleSettings.createQuery().setParam(query).fetch()
            .filter(ProductLatestDataService::hasStoredOwnerScope)
            .map(AuthorizationSettingEntity::getPermission);
    }

    private static boolean hasStoredOwnerScope(AuthorizationSettingEntity setting) {
        return setting.getActions() != null && setting.getActions().contains(Permission.ACTION_QUERY)
            && setting.getDataAccesses() != null && setting.getDataAccesses().stream().filter(Objects::nonNull).anyMatch(access ->
                Permission.ACTION_QUERY.equals(access.getAction())
                    && DataAccessConfig.DefaultType.OWN_CREATED.equals(access.getType()));
    }

    private static String creatorScope(Authentication auth, boolean restricted) {
        if (!restricted) {
            return null;
        }
        User user = auth.getUser();
        String userId = user == null ? null : user.getId();
        if (!StringUtils.hasText(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无法确定当前用户的设备数据范围");
        }
        return userId;
    }

    private Mono<Product> product(String productId, String creatorId) {
        QueryParamEntity query = QueryParamEntity.of("id", productId).doPaging(0, 1);
        restrictToCreator(query, creatorId);
        return products.query(Mono.just(query))
            .next()
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到产品")))
            .flatMap(product -> {
                if (!StringUtils.hasText(product.getMetadata())) {
                    return Mono.just(new Product(null, List.of(), List.of()));
                }
                return JetLinksDeviceMetadataCodec.getInstance().decode(product.getMetadata())
                    .map(metadata -> {
                        List<Column> columns = metadata.getProperties().stream()
                            .map(property -> new Column(property.getId(), property.getName()))
                            .toList();
                        return new Product(metadata, columns, columns.stream().map(Column::id).toList());
                    });
            });
    }

    private Mono<List<DeviceInstanceEntity>> exportBatch(String productId, String creatorId, String afterId) {
        return Mono.defer(() -> devices
            .query(Mono.just(deviceQuery(productId, creatorId, 0, EXPORT_BATCH_SIZE, afterId)))
            .collectList());
    }

    private static QueryParamEntity deviceQuery(String productId,
                                                String creatorId,
                                                int pageIndex,
                                                int pageSize,
                                                String afterId) {
        QueryParamEntity query = QueryParamEntity.of("productId", productId).doPaging(pageIndex, pageSize);
        restrictToCreator(query, creatorId);
        query.includes("id", "name");
        query.orderBy("id").asc();
        if (afterId != null) {
            query.addTerm(Term.of("id", TermType.gt, afterId));
        }
        return query;
    }

    private static void restrictToCreator(QueryParamEntity query, String creatorId) {
        if (creatorId != null) {
            query.addTerm(Term.of("creatorId", TermType.eq, creatorId));
        }
    }

    private Flux<Row> rows(Flux<DeviceInstanceEntity> roster, Product product) {
        return roster.flatMapSequential(device -> latestProperties
            .query(device.getId(), product.metadata(), product.propertyIds(), false)
            .map(property -> new Property(property.getProperty(), property.getValue(), property.getTimestamp()))
            .collectList()
            .map(properties -> {
                Long timestamp = properties.stream()
                    .map(Property::timestamp)
                    .filter(time -> time > 0)
                    .max(Long::compareTo)
                    .orElse(null);
                return new Row(device.getId(), device.getName(), timestamp, properties);
            }), DEVICE_CONCURRENCY);
    }

    public record Export(List<Column> columns, Flux<Row> rows) {
    }

    private record Product(DeviceMetadata metadata, List<Column> columns, List<String> propertyIds) {
    }

    private record QueryScope(String productCreator, String deviceCreator) {
    }
}
