package org.jetlinks.community.saas.propertycatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.hswebframework.ezorm.rdb.mapping.DefaultEntityColumnMapping;
import org.hswebframework.ezorm.rdb.mapping.defaults.DefaultReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.jpa.JpaEntityTableMetadataParser;
import org.hswebframework.ezorm.rdb.mapping.parser.DefaultDataTypeResolver;
import org.hswebframework.ezorm.rdb.mapping.parser.DefaultValueCodecResolver;
import org.hswebframework.ezorm.rdb.mapping.wrapper.EntityResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.operator.DefaultDatabaseOperator;
import org.hswebframework.web.crud.configuration.DialectProviders;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.crud.sql.DefaultR2dbcExecutor;
import org.jetlinks.community.saas.propertycatalog.dto.CatalogRequests.*;
import org.jetlinks.community.saas.propertycatalog.entity.*;
import org.jetlinks.community.saas.propertycatalog.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** Real, isolated H2 + EasyORM repositories and reactive transactions; no application services or credentials. */
class PropertyCatalogIntegrationTest {
    final ObjectMapper mapper = new ObjectMapper();
    final PropertyLibraryStore libraries = new PropertyLibraryStore();
    final PropertyCategoryStore categories = new PropertyCategoryStore();
    final PropertyCatalogItemStore items = new PropertyCatalogItemStore();
    DefaultR2dbcExecutor executor;
    PropertyCatalogService service;

    @BeforeEach void setup() {
        var connection = ConnectionFactories.get("r2dbc:h2:mem:///catalog_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        executor = new DefaultR2dbcExecutor(); executor.setDefaultFactory(connection);
        var provider = DialectProviders.lookup("h2"); executor.setBindSymbol(provider.getBindSymbol());
        var metadata = new RDBDatabaseMetadata(provider.getDialect()); metadata.addFeature(executor);
        metadata.addFeature(new org.hswebframework.web.crud.generator.CurrentTimeGenerator());
        metadata.addFeature(new org.hswebframework.web.crud.generator.SnowFlakeStringIdGenerator());
        metadata.addFeature(new org.hswebframework.web.crud.generator.DefaultIdGenerator());
        metadata.addFeature(new CatalogKeywordTerm());
        var schema = provider.createSchema("PUBLIC"); metadata.addSchema(schema); metadata.setCurrentSchema(schema);
        var operator = DefaultDatabaseOperator.of(metadata);
        configure(libraries, PropertyLibraryEntity.class, PropertyLibraryEntity::new, metadata, operator);
        configure(categories, PropertyCategoryEntity.class, PropertyCategoryEntity::new, metadata, operator);
        configure(items, PropertyCatalogItemEntity.class, PropertyCatalogItemEntity::new, metadata, operator);
        service = new PropertyCatalogService(libraries, categories, items, TransactionalOperator.create(new R2dbcTransactionManager(connection)), mapper);
    }

    <T> void configure(GenericReactiveCrudService<T, String> store, Class<T> type, Supplier<T> factory,
                       RDBDatabaseMetadata metadata, DefaultDatabaseOperator operator) {
        var parser = new JpaEntityTableMetadataParser(); parser.setDatabaseMetadata(metadata);
        parser.setDataTypeResolver(DefaultDataTypeResolver.INSTANCE); parser.setValueCodecResolver(DefaultValueCodecResolver.COMMONS);
        var table = parser.parseTableMetadata(type).orElseThrow();
        operator.ddl().createOrAlter(table).commit().reactive().block();
        var mapping = new DefaultEntityColumnMapping(table, type);
        var repository = new DefaultReactiveRepository<T, String>(operator, table, type, new EntityResultWrapper<>(factory, mapping));
        ReflectionTestUtils.setField(store, "repository", repository);
    }

    @Test void seedCreatesExactLibrariesAndRestartDoesNotRestoreDeletedOrEditedRows() throws Exception {
        JsonNode seed = seed();
        assertThat(service.initialize(seed).block()).isTrue();
        assertThat(items.inLibrary("excel-0918").count().block()).isEqualTo(353);
        assertThat(items.inLibrary("standard").count().block()).isEqualTo(57);
        var ua = items.inLibrary("excel-0918").filter(v -> "Ua".equals(v.getCode())).blockFirst();
        var ub = items.inLibrary("excel-0918").filter(v -> "Ub".equals(v.getCode())).blockFirst();
        service.deleteItem(ua.getId()).block(); ub.setName("用户维护的名称"); service.saveItem(ub.getId(), ub).block();
        assertThat(service.initialize(seed).block()).isFalse();
        assertThat(items.findById(ua.getId()).block()).isNull();
        assertThat(items.findById(ub.getId()).block().getName()).isEqualTo("用户维护的名称");
        assertThatThrownBy(() -> service.deleteLibrary("standard").block()).hasMessageContaining("不能删除");
        var standard = libraries.findById("standard").block(); standard.setEnabled(false);
        assertThatThrownBy(() -> service.saveLibrary("standard", standard).block()).hasMessageContaining("不能停用");
        standard.setEnabled(true); standard.setSeedVersion(99); service.saveLibrary("standard", standard).block();
        assertThat(libraries.findById("standard").block().getSeedVersion()).isEqualTo(1);
    }

    @Test void databaseFailureRollsBackCategoriesAndEarlierItems() {
        var library = library("project");
        executor.update("ALTER TABLE dev_property_library_item ADD CONSTRAINT fail_test CHECK (name <> 'trigger_failure')").block();
        Confirm input = confirm(row("Ua", "A相电压", "电气/电压"), row("Ub", "trigger_failure", "电气/电压"));
        assertThatThrownBy(() -> service.confirmImport(library.getId(), input).block()).hasStackTraceContaining("FAIL_TEST");
        assertThat(items.inLibrary(library.getId()).count().block()).isZero();
        assertThat(categories.inLibrary(library.getId()).count().block()).isZero();
    }

    @Test void seedFailureRollsBackAllThreeTablesAndVersion() throws Exception {
        executor.update("ALTER TABLE dev_property_library_item ADD CONSTRAINT fail_seed CHECK (code <> 'Ua')").block();
        assertThatThrownBy(() -> service.initialize(seed()).block()).hasStackTraceContaining("FAIL_SEED");
        assertThat(libraries.all().count().block()).isZero();
        assertThat(categories.inLibrary("excel-0918").count().block()).isZero();
        assertThat(items.inLibrary("excel-0918").count().block()).isZero();
    }

    @Test void categoryTreeRejectsCyclesCrossLibraryParentsAndNonemptyDeletes() {
        var one = library("one"); var two = library("two");
        var parent = category(one.getId(), "", "电气"); var child = category(one.getId(), parent.getId(), "电压");
        var foreign = category(two.getId(), "", "异库");
        parent.setParentId(child.getId());
        assertThatThrownBy(() -> service.saveCategory(parent.getId(), parent).block()).hasMessageContaining("子分类");
        child.setParentId(foreign.getId());
        assertThatThrownBy(() -> service.saveCategory(child.getId(), child).block()).hasMessageContaining("其他项目库");
        assertThatThrownBy(() -> service.deleteCategory(parent.getId()).block()).hasMessageContaining("还有");
        assertThatThrownBy(() -> service.deleteLibrary(one.getId()).block()).hasMessageContaining("请先删除");
        assertThat(service.categoryTree(one.getId()).collectList().block().get(0).getChildren()).hasSize(1);
    }

    @Test void concurrentOpposingMovesCannotCreateCycle() {
        var library = library("project"); var a = category(library.getId(), "", "A"); var b = category(library.getId(), "", "B");
        a.setParentId(b.getId()); b.setParentId(a.getId());
        var results = Flux.merge(service.saveCategory(a.getId(), a).subscribeOn(Schedulers.boundedElastic()).materialize(),
            service.saveCategory(b.getId(), b).subscribeOn(Schedulers.boundedElastic()).materialize()).collectList().block();
        assertThat(results.stream().filter(v -> v.isOnNext()).count()).isEqualTo(1);
        assertThat(results.stream().filter(v -> v.isOnError()).count()).isEqualTo(1);
        assertThat(service.categoryTree(library.getId()).collectList().block()).hasSize(1);
    }

    @Test void sameCodeAllowedAcrossLibrariesButUniqueWithinLibraryAndAdoptNeverOverwrites() throws Exception {
        service.initialize(seed()).block(); var library = library("project");
        var source = item(library.getId(), null, "Ua", "另一名称");
        assertThatThrownBy(() -> item(library.getId(), null, "Ua", "重复")).hasMessageContaining("已存在");
        var duplicate = new PropertyCatalogItemEntity(); duplicate.setId("direct-db-duplicate"); duplicate.setLibraryId(library.getId()); duplicate.setCode("Ua"); duplicate.setName("重复");
        assertThatThrownBy(() -> items.insert(duplicate).block()).hasStackTraceContaining("Unique index or primary key violation");
        Adopt adopt = new Adopt(); adopt.setItemIds(List.of(source.getId()));
        var result = service.adopt(adopt).block(); assertThat(result.getCreated()).isZero(); assertThat(result.getConflicts()).hasSize(1);
        assertThat(items.inLibrary("standard").filter(v -> v.getCode().equals("Ua")).blockFirst().getName()).isEqualTo("A相电压");
        source.setLibraryId("standard");
        assertThatThrownBy(() -> service.saveItem(source.getId(), source).block()).hasMessageContaining("不能直接移动");
    }

    @Test void clearingClassificationAndAtomicBatchValidationArePersisted() {
        var a = library("one"); var b = library("two"); var category = category(a.getId(), "", "电压");
        var first = item(a.getId(), category.getId(), "Ua", "A相电压"); var foreign = item(b.getId(), null, "Ub", "B相电压");
        BatchCategory batch = new BatchCategory(); batch.setLibraryId(a.getId()); batch.setItemIds(List.of(first.getId(), foreign.getId()));
        assertThatThrownBy(() -> service.batchCategory(batch).block()).hasMessageContaining("当前项目库");
        assertThat(items.findById(first.getId()).block().getCategoryId()).isEqualTo(category.getId());
        batch.setItemIds(List.of(first.getId())); service.batchCategory(batch).block();
        assertThat(items.findById(first.getId()).block().getCategoryId()).isNull();
        first.setCategoryId(category.getId()); service.saveItem(first.getId(), first).block();
        first.setCategoryId(null); service.saveItem(first.getId(), first).block();
        assertThat(items.findById(first.getId()).block().getCategoryId()).isNull();
    }

    @Test void databasePagingUsesLibraryCategoryDescendantsAndNameOrCode() {
        var a = library("one"); var b = library("two"); var parent = category(a.getId(), "", "电气"); var child = category(a.getId(), parent.getId(), "电压");
        item(a.getId(), child.getId(), "Ua", "A相电压"); item(a.getId(), parent.getId(), "Ub", "B相电压"); item(b.getId(), null, "Uc", "C相电压");
        ItemQuery query = new ItemQuery(); query.setLibraryId(a.getId()); query.setCategoryId(parent.getId()); query.setKeyword("电压"); query.setPageSize(1);
        var result = service.queryItems(query).block(); assertThat(result.getTotal()).isEqualTo(2); assertThat(result.getData()).hasSize(1);
        query.setPageIndex(1); assertThat(service.queryItems(query).block().getData()).hasSize(1);
        query.setKeyword("ua"); query.setPageIndex(0); assertThat(service.queryItems(query).block().getTotal()).isEqualTo(1);
        query.setIncludeChildren(false); assertThat(service.queryItems(query).block().getTotal()).isZero();
        query.setCategoryId(null); query.setKeyword("_"); assertThat(service.queryItems(query).block().getTotal()).isZero();
        query.setKeyword("%"); assertThat(service.queryItems(query).block().getTotal()).isZero();
    }

    @Test void concurrentLibraryDeleteAndCategoryCreationCannotLeaveOrphan() {
        var library = library("project"); var category = new PropertyCategoryEntity(); category.setLibraryId(library.getId()); category.setName("电压");
        Flux.merge(service.deleteLibrary(library.getId()).subscribeOn(Schedulers.boundedElastic()).materialize().cast(Object.class),
            service.saveCategory(null, category).subscribeOn(Schedulers.boundedElastic()).materialize().cast(Object.class)).collectList().block();
        var remaining = libraries.findById(library.getId()).block();
        long count = categories.inLibrary(library.getId()).count().block();
        if (remaining == null) assertThat(count).isZero(); else assertThat(count).isEqualTo(1);
    }

    @Test void previewMarksBothDuplicatesAndConfirmationRequiresResolution() {
        var library = library("project"); var preview = new Preview();
        preview.setRows(List.of(row("Eqc", "C相无功电能", ""), row("Eqc", "反向无功电度", "")));
        var checked = service.validatePreview(library.getId(), preview).block();
        assertThat(checked.getRows()).allSatisfy(row -> assertThat(row.getErrors()).contains("文件内采集项 ID 重复"));
        assertThatThrownBy(() -> service.confirmImport(library.getId(), confirm(checked.getRows().toArray(ImportRow[]::new))).block()).hasMessageContaining("ID 重复");
        checked.getRows().get(1).setCode("ReverseEq");
        assertThat(service.confirmImport(library.getId(), confirm(checked.getRows().toArray(ImportRow[]::new))).block().getCreated()).isEqualTo(2);
    }

    @Test void confirmationRevalidatesEditedRowsAndIgnoresClientErrorFlags() {
        var library = library("project"); ImportRow row = row("bad-code", "电压", ""); row.setErrors(List.of());
        assertThatThrownBy(() -> service.confirmImport(library.getId(), confirm(row)).block()).hasMessageContaining("下划线");
        row.setCode("Ua"); row.setName("a".repeat(65)); assertThatThrownBy(() -> service.confirmImport(library.getId(), confirm(row)).block()).hasMessageContaining("64");
        row.setName("电压"); row.setErrors(List.of("旧的客户端错误"));
        assertThat(service.confirmImport(library.getId(), confirm(row)).block().getCreated()).isEqualTo(1);
        assertThat(service.confirmImport(library.getId(), confirm(row)).block().getSkipped()).isEqualTo(1);
    }

    PropertyLibraryEntity library(String code) { var v = new PropertyLibraryEntity(); v.setCode(code); v.setName(code); return service.saveLibrary(null, v).block(); }
    PropertyCategoryEntity category(String lib, String parent, String name) { var v = new PropertyCategoryEntity(); v.setLibraryId(lib); v.setParentId(parent); v.setName(name); return service.saveCategory(null, v).block(); }
    PropertyCatalogItemEntity item(String lib, String category, String code, String name) { var v = new PropertyCatalogItemEntity(); v.setLibraryId(lib); v.setCategoryId(category); v.setCode(code); v.setName(name); v.setSource(Map.of("test", "source")); return service.saveItem(null, v).block(); }
    static ImportRow row(String code, String name, String category) { var row = new ImportRow(); row.setCode(code); row.setName(name); row.setCategoryPath(category); row.setRowNumber(2); return row; }
    static Confirm confirm(ImportRow... rows) { var confirm = new Confirm(); confirm.setRows(List.of(rows)); return confirm; }
    JsonNode seed() throws Exception { try (var stream = getClass().getResourceAsStream("/property-catalog/seed-v1.json")) { return mapper.readTree(stream); } }
}
