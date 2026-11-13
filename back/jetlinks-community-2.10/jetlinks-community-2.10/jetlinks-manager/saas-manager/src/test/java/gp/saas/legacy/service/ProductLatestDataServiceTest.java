package gp.saas.legacy.service;

import gp.saas.legacy.dto.ProductLatestData;
import gp.saas.legacy.dto.ProductLatestData.Column;
import gp.saas.legacy.dto.ProductLatestData.Property;
import gp.saas.legacy.dto.ProductLatestData.Row;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.Permission;
import org.hswebframework.web.authorization.User;
import org.hswebframework.web.authorization.simple.SimpleOwnCreatedDataAccessConfig;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.entity.DataAccessEntity;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.entity.DeviceProperty;
import org.jetlinks.community.device.web.DeviceInstanceController;
import org.jetlinks.community.device.web.DeviceProductController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductLatestDataServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final DeviceProductController products = mock(DeviceProductController.class);
    private final DeviceInstanceController devices = mock(DeviceInstanceController.class);
    private final LegacyLatestPropertyQuery properties = mock(LegacyLatestPropertyQuery.class);
    @SuppressWarnings("unchecked")
    private final ReactiveRepository<AuthorizationSettingEntity, String> roleSettings = mock(ReactiveRepository.class);
    private final Authentication auth = mock(Authentication.class);
    private Mono<Authentication> currentAuthentication = Mono.just(auth);
    private final ProductLatestDataService service = new ProductLatestDataService(products, devices, properties, roleSettings) {
        @Override
        protected Mono<Authentication> authentication() {
            return currentAuthentication;
        }
    };

    @BeforeEach
    void stubProductQuery() {
        when(products.query(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.flatMapMany(param -> {
                assertThat(param.getTerms()).singleElement().satisfies(term -> {
                    assertThat(term.getColumn()).isEqualTo("id");
                    assertThat(term.getValue()).isEqualTo("meter");
                });
                return Flux.just(product());
            });
        });
    }

    @Test
    void usesOnlyVisibleDevicesFromRequestedPageAndKeepsRowsWithoutData() {
        when(devices.queryPager(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.map(param -> {
                assertDeviceQuery(param, 2, 20);
                assertThat(param.getTerms()).hasSize(1);
                return PagerResult.of(42, List.of(device("a"), device("b")), param);
            });
        });
        when(properties.query(eq("a"), any(), eq(List.of("power", "on")), eq(false)))
            .thenReturn(Flux.just(property("power", 0, 40L), property("on", false, 50L)));
        when(properties.query(eq("b"), any(), eq(List.of("power", "on")), eq(false)))
            .thenReturn(Flux.empty());

        ProductLatestData page = service.query("meter", 2, 20).block(TIMEOUT);

        assertThat(page.columns()).containsExactly(new Column("power", "功率"), new Column("on", "运行"));
        assertThat(page.total()).isEqualTo(42);
        assertThat(page.data()).containsExactly(
            new Row("a", "设备 a", 50L, List.of(new Property("power", 0, 40L), new Property("on", false, 50L))),
            new Row("b", "设备 b", null, List.of()));
        verifyNoInteractions(roleSettings);
    }

    @Test
    void rejectsInvalidPagingBeforeReadingProduct() {
        assertThatThrownBy(() -> service.query("meter", Integer.MAX_VALUE, 100).block(TIMEOUT))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(products, devices, properties);
    }

    @Test
    void missingOrRestrictedProductDoesNotReadDeviceRoster() {
        doReturn(Flux.empty()).when(products).query(ArgumentMatchers.<Mono<QueryParamEntity>>any());

        assertThatThrownBy(() -> service.query("meter", 0, 20).block(TIMEOUT))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.export("meter").block(TIMEOUT))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(devices, properties);
    }

    @Test
    void devicePermissionDenialStopsPropertyReads() {
        ResponseStatusException denial = new ResponseStatusException(HttpStatus.FORBIDDEN);
        storedSettings(Set.of("device-product", "device-instance"), Flux.just(
            storedSetting("device-instance", "query", "query", "OWN_CREATED")));
        when(devices.queryPager(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenReturn(Mono.error(denial));
        when(devices.query(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenReturn(Flux.error(denial));

        assertThatThrownBy(() -> service.query("meter", 0, 20).block(TIMEOUT)).isSameAs(denial);
        assertThatThrownBy(() -> service.export("meter").flatMapMany(ProductLatestDataService.Export::rows)
            .collectList().block(TIMEOUT)).isSameAs(denial);
        verifyNoInteractions(properties);
    }

    @Test
    void requiresAuthenticationBeforeLookingUpRoster() {
        currentAuthentication = Mono.empty();

        assertThatThrownBy(() -> service.query("meter", 0, 20).block(TIMEOUT))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.export("meter").block(TIMEOUT))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(products, devices, properties);
    }

    @Test
    void appliesOwnCreatedBeforePagingAndToEveryExportBatch() {
        User user = mock(User.class);
        when(user.getId()).thenReturn("owner");
        when(auth.getUser()).thenReturn(user);
        ownCreated("device-product");
        ownCreated("device-instance");

        doAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.flatMapMany(param -> {
                assertThat(param.getTerms()).hasSize(2);
                assertCreatorTerm(param.getTerms().get(1));
                return Flux.just(product());
            });
        }).when(products).query(ArgumentMatchers.<Mono<QueryParamEntity>>any());

        List<DeviceInstanceEntity> first = IntStream.range(0, 100)
            .mapToObj(i -> device("meter-%03d".formatted(i)))
            .toList();
        DeviceInstanceEntity last = device("meter-100");
        when(devices.queryPager(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.map(param -> {
                assertDeviceQuery(param, 1, 100);
                assertThat(param.getTerms()).hasSize(2);
                assertCreatorTerm(param.getTerms().get(1));
                return PagerResult.of(101, List.of(last), param);
            });
        });
        when(devices.query(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.flatMapMany(param -> {
                assertDeviceQuery(param, 0, 100);
                assertCreatorTerm(param.getTerms().get(1));
                if (param.getTerms().size() == 2) {
                    return Flux.fromIterable(first);
                }
                assertThat(param.getTerms()).hasSize(3);
                assertThat(param.getTerms().get(2).getValue()).isEqualTo("meter-099");
                return Flux.just(last);
            });
        });
        when(properties.query(anyString(), any(), any(), anyBoolean())).thenReturn(Flux.empty());

        ProductLatestData page = service.query("meter", 1, 100).block(TIMEOUT);
        List<Row> exported = service.export("meter").flatMapMany(ProductLatestDataService.Export::rows)
            .collectList().block(TIMEOUT);

        assertThat(page.total()).isEqualTo(exported.size()).isEqualTo(101);
        assertThat(page.data()).containsExactly(exported.get(100));
        verifyNoInteractions(roleSettings);
    }

    @Test
    void appliesStoredQueryScopeOnlyToUnresolvedPermissionsForCurrentRoles() {
        ownCreated("device-product");
        storedSettings(Set.of("device-instance"), Flux.just(
            storedSetting("device-instance", "save", "query", "OWN_CREATED"),
            storedSetting("device-instance", "query", "save", "OWN_CREATED"),
            storedSetting("device-instance", "query", "query", "DENY_FIELDS"),
            storedSetting("device-instance", "query", "query", "OWN_CREATED")));
        doAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.flatMapMany(param -> {
                assertCreatorTerm(param.getTerms().get(1));
                return Flux.just(product());
            });
        }).when(products).query(ArgumentMatchers.<Mono<QueryParamEntity>>any());
        when(devices.queryPager(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.map(param -> {
                assertDeviceQuery(param, 0, 20);
                assertCreatorTerm(param.getTerms().get(1));
                return PagerResult.of(1, List.of(device("owned")), param);
            });
        });
        when(devices.query(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.flatMapMany(param -> {
                assertDeviceQuery(param, 0, 100);
                assertCreatorTerm(param.getTerms().get(1));
                return Flux.just(device("owned"));
            });
        });
        when(properties.query(anyString(), any(), any(), anyBoolean())).thenReturn(Flux.empty());

        ProductLatestData page = service.query("meter", 0, 20).block(TIMEOUT);
        List<Row> exported = service.export("meter").flatMapMany(ProductLatestDataService.Export::rows)
            .collectList().block(TIMEOUT);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.data()).isEqualTo(exported);
        assertThat(exported).extracting(Row::deviceId).containsExactly("owned");
    }

    @Test
    void ignoresStoredSettingsForOtherActionsOrAccessTypes() {
        storedSettings(Set.of("device-product", "device-instance"), Flux.just(
            storedSetting("device-product", "save", "query", "OWN_CREATED"),
            storedSetting("device-product", "query", "save", "OWN_CREATED"),
            storedSetting("device-instance", "query", "query", "DENY_FIELDS")));
        when(devices.queryPager(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.map(param -> {
                assertDeviceQuery(param, 0, 20);
                assertThat(param.getTerms()).hasSize(1);
                return PagerResult.of(0, List.of(), param);
            });
        });

        assertThat(service.query("meter", 0, 20).block(TIMEOUT).total()).isZero();
        verifyNoInteractions(properties);
    }

    @Test
    void refusesToReadRosterWhenRoleSettingsCannotBeVerified() {
        IllegalStateException unavailable = new IllegalStateException("authorization settings unavailable");
        storedSettings(Set.of("device-product", "device-instance"), Flux.error(unavailable));

        assertThatThrownBy(() -> service.query("meter", 0, 20).block(TIMEOUT)).isSameAs(unavailable);
        assertThatThrownBy(() -> service.export("meter").block(TIMEOUT)).isSameAs(unavailable);
        verifyNoInteractions(products, devices, properties);
    }

    @SuppressWarnings("unchecked")
    private void storedSettings(Set<String> permissions, Flux<AuthorizationSettingEntity> result) {
        Dimension member = mock(Dimension.class);
        when(member.getId()).thenReturn("current-role");
        when(auth.getDimensions("role")).thenReturn(List.of(member));
        User user = mock(User.class);
        when(user.getId()).thenReturn("owner");
        when(auth.getUser()).thenReturn(user);

        ReactiveQuery<AuthorizationSettingEntity> query = mock(ReactiveQuery.class);
        when(roleSettings.createQuery()).thenReturn(query);
        when(query.setParam(any())).thenAnswer(call -> {
            QueryParamEntity param = call.getArgument(0);
            assertThat(param.getTerms()).hasSize(4);
            assertSettingTerm(param, "dimensionType", TermType.eq, "role");
            assertSettingTerm(param, "dimensionTarget", TermType.in, Set.of("current-role"));
            assertSettingTerm(param, "state", TermType.eq, (byte) 1);
            assertSettingTerm(param, "permission", TermType.in, permissions);
            return query;
        });
        when(query.fetch()).thenReturn(result);
    }

    private static void assertSettingTerm(QueryParamEntity param, String column, String termType, Object value) {
        assertThat(param.getTerms()).filteredOn(term -> column.equals(term.getColumn())).singleElement().satisfies(term -> {
            assertThat(term.getTermType()).isEqualTo(termType);
            assertThat(term.getValue()).isEqualTo(value);
        });
    }

    private static AuthorizationSettingEntity storedSetting(String permission, String settingAction,
                                                            String accessAction, String accessType) {
        DataAccessEntity access = new DataAccessEntity();
        access.setAction(accessAction);
        access.setType(accessType);
        AuthorizationSettingEntity setting = new AuthorizationSettingEntity();
        setting.setPermission(permission);
        setting.setActions(Set.of(settingAction));
        setting.setDataAccesses(List.of(access));
        return setting;
    }

    private void ownCreated(String resource) {
        Permission permission = mock(Permission.class);
        SimpleOwnCreatedDataAccessConfig access = new SimpleOwnCreatedDataAccessConfig();
        access.setAction(Permission.ACTION_QUERY);
        when(permission.getDataAccesses(Permission.ACTION_QUERY)).thenReturn(Set.of(access));
        when(auth.getPermission(resource)).thenReturn(Optional.of(permission));
    }

    private static void assertCreatorTerm(Term term) {
        assertThat(term.getColumn()).isEqualTo("creatorId");
        assertThat(term.getTermType()).isEqualTo(TermType.eq);
        assertThat(term.getValue()).isEqualTo("owner");
    }

    @Test
    void exportsAllScopedBatchesInIdOrder() {
        List<DeviceInstanceEntity> first = IntStream.range(0, 100)
            .mapToObj(i -> device("meter-%03d".formatted(i)))
            .toList();
        when(devices.query(ArgumentMatchers.<Mono<QueryParamEntity>>any())).thenAnswer(call -> {
            Mono<QueryParamEntity> query = call.getArgument(0);
            return query.flatMapMany(param -> {
                assertDeviceQuery(param, 0, 100);
                if (param.getTerms().size() == 1) {
                    return Flux.fromIterable(first);
                }
                assertThat(param.getTerms()).hasSize(2);
                Term cursor = param.getTerms().get(1);
                assertThat(cursor.getColumn()).isEqualTo("id");
                assertThat(cursor.getTermType()).isEqualTo(TermType.gt);
                assertThat(cursor.getValue()).isEqualTo("meter-099");
                return Flux.just(device("meter-100"));
            });
        });
        when(properties.query(anyString(), any(), any(), anyBoolean())).thenReturn(Flux.empty());

        List<String> ids = service.export("meter").flatMapMany(ProductLatestDataService.Export::rows)
            .map(Row::deviceId)
            .collectList()
            .block(TIMEOUT);

        assertThat(ids).containsExactlyElementsOf(IntStream.rangeClosed(0, 100)
            .mapToObj(i -> "meter-%03d".formatted(i)).toList());
    }

    private static void assertDeviceQuery(QueryParamEntity query, int pageIndex, int pageSize) {
        assertThat(query.isPaging()).isTrue();
        assertThat(query.getPageIndex()).isEqualTo(pageIndex);
        assertThat(query.getPageSize()).isEqualTo(pageSize);
        assertThat(query.getSorts()).singleElement().satisfies(sort -> {
            assertThat(sort.getName()).isEqualTo("id");
            assertThat(sort.getOrder()).isEqualTo("asc");
        });
        assertThat(query.getTerms().get(0)).satisfies(term -> {
            assertThat(term.getColumn()).isEqualTo("productId");
            assertThat(term.getValue()).isEqualTo("meter");
        });
    }

    private static DeviceInstanceEntity device(String id) {
        DeviceInstanceEntity device = new DeviceInstanceEntity();
        device.setId(id);
        device.setName("设备 " + id);
        return device;
    }

    private static DeviceProductEntity product() {
        DeviceProductEntity product = new DeviceProductEntity();
        product.setId("meter");
        product.setMetadata("""
            {"properties":[
              {"id":"power","name":"功率","valueType":{"type":"double"}},
              {"id":"on","name":"运行","valueType":{"type":"boolean"}}
            ]}
            """);
        return product;
    }

    private static DeviceProperty property(String id, Object value, long time) {
        DeviceProperty property = new DeviceProperty();
        property.setProperty(id);
        property.setValue(value);
        property.setTimestamp(time);
        return property;
    }
}
