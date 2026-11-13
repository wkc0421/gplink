package org.jetlinks.community.device.service.data;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.timescaledb.thing.counter.*;
import org.jetlinks.community.device.service.data.HistoryStatisticsManagementService.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryStatisticsManagementServiceTest {
    @Test
    void productQueryEnrichesRealProductRowWithoutLookingUpNullDeviceId() {
        Fixture fixture = new Fixture();
        when(fixture.auth.hasPermission("device-product", "query")).thenReturn(true);
        DeviceProductEntity product = new DeviceProductEntity(); product.setId("p1"); product.setName("Product one");
        product.setMetadata("{\"properties\":[{\"id\":\"E\",\"name\":\"Energy\",\"valueType\":{\"type\":\"double\"}}]}");
        ReactiveQuery<DeviceProductEntity> productQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(fixture.products.createQuery()).thenReturn(productQuery);
        when(productQuery.fetch()).thenReturn(Flux.just(product));
        when(fixture.counter.configuredProductIds()).thenReturn(Flux.just("p1"));
        CounterPolicyRow policy = new CounterPolicyRow(); policy.setProductId("p1"); policy.setPropertyId("E");
        policy.setMode("ENABLE"); policy.setEnabled(true); policy.setEligible(true); policy.setConfig(new CounterConfig());
        CounterPolicyPage page = new CounterPolicyPage(); page.setPageSize(20); page.setTotal(1); page.setData(List.of(policy));
        when(fixture.counter.queryPolicies(any())).thenReturn(Mono.just(page));
        Query request = new Query(); request.setScope("PRODUCT"); request.setProductIds(List.of());
        request.setDeviceIds(List.of()); request.setPropertyIds(List.of());

        Page<Row> result = fixture.service.query(request).block();

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getProductName()).isEqualTo("Product one");
        assertThat(result.getData().get(0).getPropertyName()).isEqualTo("Energy");
        assertThat(result.getData().get(0).getDeviceId()).isNull();
        verify(fixture.devices, never()).createQuery();
    }

    @Test
    void defaultDeviceViewTreatsEmptyUiFiltersAsUnfilteredWithoutBroadeningResolvedProducts() {
        Fixture fixture = new Fixture(); fixture.allow("query");
        DeviceProductEntity product = new DeviceProductEntity(); product.setId("p1");
        ReactiveQuery<DeviceProductEntity> productQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(fixture.products.createQuery()).thenReturn(productQuery);
        when(productQuery.fetch()).thenReturn(Flux.just(product));
        when(fixture.counter.configuredProductIds()).thenReturn(Flux.just("p1"));
        Query request = new Query(); request.setScope("DEVICE"); request.setEnabled(true);
        request.setProductIds(List.of()); request.setDeviceIds(List.of()); request.setPropertyIds(List.of());

        assertThat(fixture.service.query(request).block().getData()).isEmpty();

        ArgumentCaptor<CounterPolicyQuery> captured = ArgumentCaptor.forClass(CounterPolicyQuery.class);
        verify(fixture.counter).queryPolicies(captured.capture());
        assertThat(captured.getValue().getProductIds()).containsExactly("p1");
        assertThat(captured.getValue().getDeviceIds()).isNull();
        assertThat(captured.getValue().getPropertyIds()).isNull();
        assertThat(captured.getValue().getEnabled()).isTrue();
        assertThat(request.getDeviceIds()).isEmpty();
    }

    @Test
    void emptyResolvedProductFilterDoesNotQueryAllPolicies() {
        Fixture fixture = new Fixture(); fixture.allow("query");
        ReactiveQuery<DeviceProductEntity> productQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(fixture.products.createQuery()).thenReturn(productQuery);
        when(productQuery.fetch()).thenReturn(Flux.empty());
        Query request = new Query(); request.setScope("DEVICE"); request.setProductIds(List.of("removed"));
        request.setDeviceIds(List.of()); request.setPropertyIds(List.of());

        assertThat(fixture.service.query(request).block().getTotal()).isZero();
        verify(fixture.counter, never()).queryPolicies(any());
    }

    @Test
    void emptyCandidateTargetsAreStillRejectedInsteadOfEnumeratingEveryDevice() {
        Fixture fixture = new Fixture(); fixture.allow("query");
        CandidateQuery request = new CandidateQuery(); request.setScope("DEVICE"); request.setDeviceIds(List.of());
        StepVerifier.create(fixture.service.candidates(request)).expectError(ResponseStatusException.class).verify();
        verify(fixture.counter, never()).queryPolicies(any());
    }

    @Test
    void readOnlyPostUsesScopeSpecificQueryPermission() {
        Fixture fixture = new Fixture();
        when(fixture.auth.hasPermission("device-instance", "query")).thenReturn(true);
        StepVerifier.create(fixture.service.authorize("DEVICE", "query")).expectNext(fixture.auth).verifyComplete();
        StepVerifier.create(fixture.service.authorize("DEVICE", "save"))
            .expectErrorMatches(error -> error instanceof ResponseStatusException
                && ((ResponseStatusException) error).getStatusCode() == HttpStatus.FORBIDDEN).verify();
        StepVerifier.create(fixture.service.authorize("PRODUCT", "query")).expectError(ResponseStatusException.class).verify();
        StepVerifier.create(fixture.service.authorize("UNKNOWN", "query")).expectError(ResponseStatusException.class).verify();
    }

    @Test
    void candidatesIncludeCustomNumericPropertiesAndPageBeforeReturning() {
        Fixture fixture = new Fixture(); fixture.allow("query"); fixture.device();
        CounterPolicyPage empty = new CounterPolicyPage(); empty.setData(List.of()); empty.setPageSize(200);
        when(fixture.counter.queryPolicies(any())).thenReturn(Mono.just(empty));
        CandidateQuery query = new CandidateQuery(); query.setScope("DEVICE"); query.setDeviceIds(List.of("d1"));
        query.setPageSize(1); query.setPageIndex(1);
        Page<Row> result = fixture.service.candidates(query).block();
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getPropertyId()).isEqualTo("zCustom");
        assertThat(result.getData().get(0).getPropertySource()).isEqualTo("DEVICE");
        verify(fixture.devices, never()).getDeviceDetail(anyString());
        verify(fixture.metadata, never()).ensureInitialized();
        verify(fixture.counter, never()).upsertDevice(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void batchReportsInvalidItemWithoutUndoingSuccessfulDeviceItem() {
        Fixture fixture = new Fixture(); fixture.allow("save"); fixture.device();
        when(fixture.counter.upsertDevice(eq("p1"), eq("zCustom"), eq("d1"), eq(true), anyString(), eq("operator")))
            .thenReturn(Mono.just(new CounterPolicyRow()));
        BatchRequest request = new BatchRequest();
        request.setItems(List.of(item("zCustom", "ENABLE"), item("label", "ENABLE")));
        BatchResult result = fixture.service.batch(request).block();
        assertThat(result.getSucceeded()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getItems().get(1).getMessage()).contains("数值类型");
        verify(fixture.counter, never()).upsert(anyString(), anyString(), anyBoolean(), anyString(), anyString());
        verify(fixture.counter, never()).upsertDevice(eq("p1"), eq("label"), anyString(), any(), anyString(), anyString());
    }

    @Test
    void disableAndRestoreRemainAvailableAfterPropertyWasRemoved() {
        Fixture fixture = new Fixture(); fixture.allow("save"); fixture.device();
        when(fixture.counter.upsertDevice(eq("p1"), eq("removed"), eq("d1"), nullable(Boolean.class), anyString(), eq("operator")))
            .thenReturn(Mono.just(new CounterPolicyRow()));
        when(fixture.counter.queryPolicies(any())).thenReturn(Mono.just(policyPage("ENABLE")), Mono.just(policyPage("DISABLE")));
        BatchRequest request = new BatchRequest(); request.setItems(List.of(item("removed", "DISABLE"), item("removed", "INHERIT")));
        assertThat(fixture.service.batch(request).block().getSucceeded()).isEqualTo(2);
        verify(fixture.counter).upsertDevice(eq("p1"), eq("removed"), eq("d1"), eq(false), anyString(), eq("operator"));
        verify(fixture.counter).upsertDevice(eq("p1"), eq("removed"), eq("d1"), isNull(), anyString(), eq("operator"));
    }

    @Test
    void repeatedEnableIsReportedAsUnchangedWithoutRewritingPolicy() {
        Fixture fixture = new Fixture(); fixture.allow("save"); fixture.device();
        CounterPolicyPage current = policyPage("ENABLE"); current.getData().get(0).setEligible(true);
        current.getData().get(0).setConfig(new CounterConfig());
        when(fixture.counter.queryPolicies(any())).thenReturn(Mono.just(current));
        BatchRequest request = new BatchRequest(); request.setItems(List.of(item("zCustom", "ENABLE")));
        BatchResult result = fixture.service.batch(request).block();
        assertThat(result.getSucceeded()).isEqualTo(1); assertThat(result.getItems().get(0).isChanged()).isFalse();
        verify(fixture.counter, never()).upsertDevice(anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(fixture.metadata, never()).syncDevice(anyString());
    }

    @Test
    void deviceCannotReadOrCancelSharedProductTask() {
        Fixture fixture = new Fixture(); fixture.allow("save"); fixture.allow("query"); fixture.device();
        CounterTask shared = new CounterTask(); shared.setId("task"); shared.setProductId("p1");
        when(fixture.counter.task("task")).thenReturn(Mono.just(shared));
        StepVerifier.create(fixture.service.deviceTask("d1", "task", false)).expectError(ResponseStatusException.class).verify();
        StepVerifier.create(fixture.service.deviceTask("d1", "task", true)).expectError(ResponseStatusException.class).verify();
        verify(fixture.counter, never()).cancel(anyString());
    }

    @Test
    void deviceTaskCancellationRequiresOnlySaveAndVerifiesDeviceOwnership() {
        Fixture fixture = new Fixture(); fixture.allow("save"); fixture.device();
        CounterTask task = new CounterTask(); task.setId("task"); task.setProductId("p1"); task.setDeviceId("d1");
        when(fixture.counter.task("task")).thenReturn(Mono.just(task)); when(fixture.counter.cancel("task")).thenReturn(Mono.just(task));
        assertThat(fixture.service.deviceTask("d1", "task", true).block()).isSameAs(task);
        task.setDeviceId("another");
        StepVerifier.create(fixture.service.deviceTask("d1", "task", true)).expectError(ResponseStatusException.class).verify();
        verify(fixture.counter, times(1)).cancel("task");
    }

    @Test
    void mixedScopeBatchIsAuthorizedBeforeAnyWrite() {
        Fixture fixture = new Fixture(); fixture.allow("save");
        BatchItem productItem = item("energy", "ENABLE"); productItem.setScope("PRODUCT"); productItem.setProductId("p1");
        BatchRequest request = new BatchRequest(); request.setItems(List.of(item("energy", "ENABLE"), productItem));
        StepVerifier.create(fixture.service.batch(request)).expectError(ResponseStatusException.class).verify();
        verifyNoInteractions(fixture.counter);
    }

    private static BatchItem item(String propertyId, String mode) {
        BatchItem item = new BatchItem(); item.setScope("DEVICE"); item.setDeviceId("d1"); item.setPropertyId(propertyId); item.setMode(mode);
        return item;
    }

    private static CounterPolicyPage policyPage(String mode) {
        CounterPolicyPage page = new CounterPolicyPage(); page.setPageSize(200);
        if (mode == null) page.setData(List.of());
        else { CounterPolicyRow row = new CounterPolicyRow(); row.setMode(mode); row.setConfig(new CounterConfig()); page.setData(List.of(row)); page.setTotal(1); }
        return page;
    }

    private static class Fixture {
        final Authentication auth = mock(Authentication.class, RETURNS_DEEP_STUBS);
        final LocalDeviceProductService products = mock(LocalDeviceProductService.class);
        final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        final HistoryStatisticsMetadataService metadata = mock(HistoryStatisticsMetadataService.class);
        final CounterSnapshotService counter = mock(CounterSnapshotService.class);
        final HistoryStatisticsManagementService service;
        Fixture() {
            ObjectProvider<CounterSnapshotService> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(counter);
            when(auth.getUser().getId()).thenReturn("operator");
            when(metadata.ensureInitialized()).thenReturn(Mono.empty());
            when(metadata.awaitInitialized()).thenReturn(Mono.empty());
            when(metadata.syncDevice(anyString())).thenReturn(Mono.empty());
            when(counter.queryPolicies(any())).thenReturn(Mono.just(policyPage(null)));
            service = new HistoryStatisticsManagementService(products, devices, new DeviceDataStorageProperties(), provider, metadata) {
                @Override protected Mono<Authentication> authentication() { return Mono.just(auth); }
            };
        }
        void allow(String action) { when(auth.hasPermission("device-instance", action)).thenReturn(true); }
        void device() {
            DeviceProductEntity product = new DeviceProductEntity(); product.setId("p1"); product.setName("Product");
            product.setMetadata("{\"properties\":[{\"id\":\"energy\",\"name\":\"Energy\",\"valueType\":{\"type\":\"double\"}}]}");
            DeviceInstanceEntity device = new DeviceInstanceEntity(); device.setId("d1"); device.setName("Device"); device.setProductId("p1");
            device.setDeriveMetadata("{\"properties\":[{\"id\":\"zCustom\",\"name\":\"Custom\",\"valueType\":{\"type\":\"double\"}},{\"id\":\"label\",\"name\":\"Label\",\"valueType\":{\"type\":\"string\"}}]}");
            when(metadata.device("d1")).thenReturn(Mono.just(device)); when(metadata.product("p1")).thenReturn(Mono.just(product));
        }
    }
}
