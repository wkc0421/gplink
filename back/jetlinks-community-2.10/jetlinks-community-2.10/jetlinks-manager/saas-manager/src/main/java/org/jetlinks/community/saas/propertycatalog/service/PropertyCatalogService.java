package org.jetlinks.community.saas.propertycatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.ValidationException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.saas.propertycatalog.dto.CatalogRequests.*;
import org.jetlinks.community.saas.propertycatalog.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PropertyCatalogService {
    public static final String STANDARD = "standard";
    public static final int MAX_IMPORT_ROWS = 10000;
    private final PropertyLibraryStore libraries;
    private final PropertyCategoryStore categories;
    private final PropertyCatalogItemStore items;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;

    public Flux<PropertyLibraryEntity> libraries(Boolean enabled) {
        return libraries.all().filter(v -> enabled == null || enabled.equals(v.getEnabled()))
            .sort(Comparator.comparingInt((PropertyLibraryEntity v) -> number(v.getSortIndex())).thenComparing(PropertyLibraryEntity::getId));
    }

    public Mono<PropertyLibraryEntity> requireLibrary(String id) {
        return Mono.defer(() -> {
            requireText(id, "项目库", 64);
            return libraries.findById(id).switchIfEmpty(failure("项目库不存在"));
        });
    }

    public Mono<PropertyLibraryEntity> saveLibrary(String id, PropertyLibraryEntity input) {
        return atomic(Mono.defer(() -> {
            input.setCode(requireText(input.getCode(), "项目库编码", 64));
            if (!input.getCode().matches("^[a-zA-Z0-9_-]+$")) return failure("项目库编码只能包含字母、数字、下划线或短横线");
            input.setName(requireText(input.getName(), "项目库名称", 128));
            length(input.getDescription(), "项目库说明", 1024);
            if (id == null && STANDARD.equals(input.getCode())) return failure("标准库已由系统维护");
            Mono<PropertyLibraryEntity> existing = id == null ? Mono.just(new PropertyLibraryEntity()) : lockedLibrary(id);
            return existing.flatMap(old -> libraries.all().filter(v -> input.getCode().equals(v.getCode()) && !Objects.equals(id, v.getId()))
                .hasElements().flatMap(duplicate -> {
                    if (duplicate) return failure("项目库编码已存在");
                    if (STANDARD.equals(id) && !STANDARD.equals(input.getCode())) return failure("标准库编码不能修改");
                    if (STANDARD.equals(id) && Boolean.FALSE.equals(input.getEnabled())) return failure("标准库不能停用");
                    PropertyLibraryEntity saved = new PropertyLibraryEntity();
                    saved.setId(id == null ? nextId() : id);
                    saved.setCode(input.getCode()); saved.setName(input.getName()); saved.setDescription(input.getDescription());
                    saved.setEnabled(input.getEnabled() == null ? true : input.getEnabled()); saved.setSortIndex(number(input.getSortIndex()));
                    saved.setSeedVersion(old.getSeedVersion());
                    return (id == null ? libraries.insert(saved) : libraries.updateById(id, saved)).then(libraries.findById(saved.getId()));
                }));
        }));
    }

    public Mono<Boolean> deleteLibrary(String id) {
        return atomic(lockedLibrary(id).flatMap(library -> {
            if (STANDARD.equals(id)) return failure("标准库不能删除");
            return Mono.zip(categories.inLibrary(id).hasElements(), items.inLibrary(id).hasElements())
                .flatMap(nonempty -> nonempty.getT1() || nonempty.getT2() ? failure("请先删除项目库内的分类和采集项")
                    : libraries.deleteById(id).map(count -> count > 0));
        }));
    }

    public Flux<PropertyCategoryEntity> categoryTree(String libraryId) {
        return requireLibrary(libraryId).thenMany(categories.inLibrary(libraryId)).collectList()
            .flatMapMany(list -> Flux.fromIterable(tree(list)));
    }

    public Mono<PropertyCategoryEntity> saveCategory(String id, PropertyCategoryEntity input) {
        return atomic(Mono.defer(() -> {
            input.setName(requireText(input.getName(), "分类名称", 128));
            if (input.getName().contains("/")) return failure("分类名称不能包含 /，请创建子分类");
            input.setParentId(trim(input.getParentId()));
            return lockedLibrary(input.getLibraryId()).then(categories.inLibrary(input.getLibraryId()).collectList())
                .flatMap(list -> {
                    if (id != null && list.stream().noneMatch(v -> id.equals(v.getId()))) return failure("分类不存在或不属于该项目库");
                    validateParent(id, input.getParentId(), list);
                    if (list.stream().anyMatch(v -> !Objects.equals(id, v.getId()) && input.getParentId().equals(trim(v.getParentId())) && input.getName().equals(v.getName())))
                        return failure("同级分类名称已存在");
                    PropertyCategoryEntity saved = new PropertyCategoryEntity();
                    saved.setId(id == null ? nextId() : id); saved.setLibraryId(input.getLibraryId());
                    saved.setParentId(input.getParentId()); saved.setName(input.getName()); saved.setSortIndex(number(input.getSortIndex()));
                    return (id == null ? categories.insert(saved) : categories.updateById(id, saved)).then(categories.findById(saved.getId()));
                });
        }));
    }

    public Mono<Boolean> deleteCategory(String id) {
        return atomic(categories.findById(id).switchIfEmpty(failure("分类不存在"))
            .flatMap(category -> lockedLibrary(category.getLibraryId()).then(Mono.zip(
                categories.inLibrary(category.getLibraryId()).any(v -> id.equals(v.getParentId())),
                items.inLibrary(category.getLibraryId()).any(v -> id.equals(v.getCategoryId())))
                .flatMap(nonempty -> nonempty.getT1() || nonempty.getT2() ? failure("分类下还有子分类或采集项，不能删除")
                    : categories.deleteById(id).map(count -> count > 0)))));
    }

    public Mono<PagerResult<PropertyCatalogItemEntity>> queryItems(ItemQuery request) {
        return Mono.defer(() -> {
            if (request.getPageIndex() < 0 || request.getPageSize() < 1 || request.getPageSize() > 200) return failure("分页参数无效，每页最多 200 项");
            return requireLibrary(request.getLibraryId()).then(categories.inLibrary(request.getLibraryId()).collectList())
                .flatMap(list -> {
                    Set<String> ids = categorySelection(request.getCategoryId(), request.isIncludeChildren(), list);
                    QueryParamEntity query = new QueryParamEntity();
                    query.setPageIndex(request.getPageIndex()); query.setPageSize(request.getPageSize());
                    var where = query.toQuery().where("libraryId", request.getLibraryId());
                    if (ids != null) {
                        if (ids.equals(Set.of(""))) where.isNull("categoryId");
                        else where.in("categoryId", ids);
                    }
                    if (request.getEnabled() != null) where.and("enabled", request.getEnabled());
                    String keyword = trim(request.getKeyword());
                    length(keyword, "搜索关键字", 128);
                    if (!keyword.isEmpty()) where.nest().and("code", CatalogKeywordTerm.TYPE, keyword).or("name", CatalogKeywordTerm.TYPE, keyword).end();
                    where.orderByAsc("sortIndex").orderByAsc("code");
                    return items.queryPager(query);
                });
        });
    }

    public Mono<PropertyCatalogItemEntity> saveItem(String id, PropertyCatalogItemEntity input) {
        return atomic(Mono.defer(() -> {
            input.setCode(code(input.getCode())); input.setName(requireText(input.getName(), "采集项名称", 64));
            length(input.getRemark(), "备注", 1024);
            return lockedLibrary(input.getLibraryId()).then(validateCategory(input.getLibraryId(), input.getCategoryId()))
                .then(id == null ? Mono.just(new PropertyCatalogItemEntity()) : items.findById(id).switchIfEmpty(failure("采集项不存在")))
                .flatMap(old -> {
                    if (id != null && !input.getLibraryId().equals(old.getLibraryId())) return failure("不能直接移动采集项到其他项目库");
                    return items.inLibrary(input.getLibraryId()).any(v -> input.getCode().equals(v.getCode()) && !Objects.equals(id, v.getId()))
                        .flatMap(duplicate -> {
                            if (duplicate) return failure("该项目库内采集项 ID 已存在");
                            PropertyCatalogItemEntity saved = copyItem(input);
                            saved.setId(id == null ? nextId() : id); saved.setCategoryId(emptyToNull(input.getCategoryId()));
                            saved.setEnabled(input.getEnabled() == null ? true : input.getEnabled()); saved.setSortIndex(number(input.getSortIndex()));
                            if (id != null) saved.setSource(old.getSource());
                            return (id == null ? items.insert(saved) : updateItem(saved)).then(items.findById(saved.getId()));
                        });
                });
        }));
    }

    public Mono<Boolean> deleteItem(String id) {
        return atomic(items.findById(id).flatMap(item -> lockedLibrary(item.getLibraryId()).then(items.deleteById(id)).map(count -> count > 0)).defaultIfEmpty(false));
    }

    public Mono<Integer> batchCategory(BatchCategory request) {
        return atomic(Mono.defer(() -> lockedLibrary(request.getLibraryId())
            .then(validateCategory(request.getLibraryId(), request.getCategoryId()))
            .then(loadSelected(request.getItemIds()))
            .flatMap(selected -> {
                if (selected.stream().anyMatch(v -> !request.getLibraryId().equals(v.getLibraryId()))) return failure("所选采集项必须属于当前项目库");
                return Flux.fromIterable(selected).concatMap(v -> {
                    v.setCategoryId(emptyToNull(request.getCategoryId())); return updateItem(v);
                }).reduce(0, Integer::sum);
            })));
    }

    public Mono<Preview> validatePreview(String libraryId, Preview preview) {
        return requireLibrary(libraryId).then(items.inLibrary(libraryId).collectList()).map(existing -> {
            Map<String, PropertyCatalogItemEntity> known = existing.stream().collect(Collectors.toMap(PropertyCatalogItemEntity::getCode, Function.identity()));
            Map<String, Long> counts = preview.getRows().stream().collect(Collectors.groupingBy(v -> trim(v.getCode()), LinkedHashMap::new, Collectors.counting()));
            for (ImportRow row : preview.getRows()) {
                row.setCode(trim(row.getCode())); row.setName(trim(row.getName()));
                row.setErrors(rowErrors(row));
                if (counts.get(row.getCode()) > 1) row.getErrors().add("文件内采集项 ID 重复");
                PropertyCatalogItemEntity old = known.get(row.getCode());
                if (old != null) preview.getWarnings().add("第 " + row.getRowNumber() + " 行：" + row.getCode() +
                    (old.getName().equals(row.getName()) ? " 已存在，确认时跳过" : " 与现有名称冲突，确认时不会覆盖"));
            }
            return preview;
        });
    }

    public Mono<ImportResult> confirmImport(String libraryId, Confirm request) {
        return atomic(Mono.defer(() -> {
            validateRows(request.getRows());
            return lockedLibrary(libraryId).then(importRows(libraryId, request.getRows()));
        }));
    }

    public Mono<ImportResult> adopt(Adopt request) {
        return atomic(lockedLibrary(STANDARD).then(validateCategory(STANDARD, request.getCategoryId()))
            .then(loadSelected(request.getItemIds())).flatMap(selected -> items.inLibrary(STANDARD).collectList().flatMap(existing -> {
                Map<String, PropertyCatalogItemEntity> known = existing.stream().collect(Collectors.toMap(PropertyCatalogItemEntity::getCode, Function.identity()));
                ImportResult result = new ImportResult();
                return Flux.fromIterable(selected).concatMap(item -> {
                    PropertyCatalogItemEntity old = known.get(item.getCode());
                    if (old != null) { classifyExisting(result, item.getCode(), item.getName(), old); return Mono.empty(); }
                    PropertyCatalogItemEntity copy = copyItem(item);
                    copy.setId(nextId()); copy.setLibraryId(STANDARD); copy.setCategoryId(emptyToNull(request.getCategoryId()));
                    Map<String, Object> source = new LinkedHashMap<>(); if (item.getSource() != null) source.putAll(item.getSource());
                    source.put("libraryId", item.getLibraryId()); source.put("itemId", item.getId()); copy.setSource(source);
                    return items.insert(copy).doOnNext(count -> { known.put(copy.getCode(), copy); result.setCreated(result.getCreated() + 1); }).then();
                }).then(Mono.just(result));
            })));
    }

    private Mono<List<PropertyCatalogItemEntity>> loadSelected(List<String> ids) {
        return Mono.defer(() -> {
            if (ids == null || ids.isEmpty() || ids.size() > MAX_IMPORT_ROWS) return failure("请选择 1 至 10000 个采集项");
            return Flux.fromIterable(new LinkedHashSet<>(ids)).concatMap(id -> {
                if (id == null || id.isBlank()) return failure("采集项标识不能为空");
                return items.findById(id).switchIfEmpty(failure("所选采集项不存在：" + id));
            }).collectList();
        });
    }

    private Mono<Void> validateCategory(String libraryId, String categoryId) {
        if (trim(categoryId).isEmpty()) return Mono.empty();
        return categories.findById(categoryId).switchIfEmpty(failure("分类不存在"))
            .flatMap(category -> libraryId.equals(category.getLibraryId()) ? Mono.empty() : failure("分类不属于当前项目库"));
    }

    private Mono<ImportResult> importRows(String libraryId, List<ImportRow> rows) {
        return Mono.zip(items.inLibrary(libraryId).collectList(), categories.inLibrary(libraryId).collectList())
            .flatMap(state -> {
                Map<String, PropertyCatalogItemEntity> known = state.getT1().stream().collect(Collectors.toMap(PropertyCatalogItemEntity::getCode, Function.identity()));
                List<PropertyCategoryEntity> categoryList = new ArrayList<>(state.getT2());
                ImportResult result = new ImportResult();
                return Flux.fromIterable(rows).index().concatMap(indexed -> {
                    ImportRow row = indexed.getT2(); String itemCode = trim(row.getCode()); String name = trim(row.getName());
                    PropertyCatalogItemEntity old = known.get(itemCode);
                    if (old != null) { classifyExisting(result, itemCode, name, old); return Mono.empty(); }
                    return ensureCategory(libraryId, row.getCategoryPath(), categoryList).flatMap(categoryId -> {
                        PropertyCatalogItemEntity item = new PropertyCatalogItemEntity();
                        item.setId(nextId()); item.setLibraryId(libraryId); item.setCategoryId(emptyToNull(categoryId));
                        item.setCode(itemCode); item.setName(name); item.setEnabled(true); item.setSortIndex(row.getSortIndex() == null ? indexed.getT1().intValue() : row.getSortIndex());
                        item.setRemark(row.getRemark()); item.setSource(row.getSource());
                        return items.insert(item).doOnNext(count -> { known.put(itemCode, item); result.setCreated(result.getCreated() + 1); }).then();
                    });
                }).then(Mono.just(result));
            });
    }

    private Mono<String> ensureCategory(String libraryId, String path, List<PropertyCategoryEntity> known) {
        Mono<String> parent = Mono.just("");
        for (String segment : pathSegments(path)) {
            parent = parent.flatMap(parentId -> {
                Optional<PropertyCategoryEntity> existing = known.stream().filter(v -> parentId.equals(trim(v.getParentId())) && segment.equals(v.getName())).findFirst();
                if (existing.isPresent()) return Mono.just(existing.get().getId());
                PropertyCategoryEntity value = new PropertyCategoryEntity(); value.setId(nextId()); value.setLibraryId(libraryId);
                value.setParentId(parentId); value.setName(segment); value.setSortIndex(known.size());
                return categories.insert(value).doOnNext(count -> known.add(value)).thenReturn(value.getId());
            });
        }
        return parent;
    }

    /** Version is committed with all three tables and survives user deletions. */
    public Mono<Boolean> initialize(JsonNode seed) {
        return atomic(Mono.defer(() -> libraries.findById(STANDARD).defaultIfEmpty(new PropertyLibraryEntity()).flatMap(standard -> {
            int version = seed.path("version").asInt();
            if (version != 1) return failure("不支持的采集项库初始化版本");
            if (number(standard.getSeedVersion()) >= version) return Mono.just(false);
            Mono<?> createStandard;
            if (standard.getId() == null) {
                standard.setId(STANDARD); standard.setCode(STANDARD); standard.setName("标准库"); standard.setEnabled(true); standard.setSortIndex(0);
                createStandard = libraries.insert(standard);
            } else createStandard = Mono.empty();
            PropertyLibraryEntity project = new PropertyLibraryEntity();
            project.setId(seed.path("library").path("id").asText()); project.setCode(seed.path("library").path("code").asText());
            project.setName(seed.path("library").path("name").asText()); project.setEnabled(true); project.setSortIndex(1);
            List<ImportRow> rows = new ArrayList<>();
            for (JsonNode value : seed.path("items")) rows.add(mapper.convertValue(value, ImportRow.class));
            validateRows(rows);
            Set<String> standardCodes = new HashSet<>(); seed.path("standardCodes").forEach(v -> standardCodes.add(v.asText()));
            List<ImportRow> defaults = rows.stream().filter(v -> standardCodes.contains(v.getCode())).collect(Collectors.toList());
            if (defaults.size() != standardCodes.size()) return failure("标准库初始化编码缺失或重复");
            return createStandard.then(lockedLibrary(STANDARD)).flatMap(lockedStandard -> {
                if (number(lockedStandard.getSeedVersion()) >= version) return Mono.just(false);
                return libraries.findById(project.getId()).switchIfEmpty(Mono.defer(() -> libraries.insert(project).thenReturn(project)))
                    .then(lockedLibrary(project.getId())).then(importRows(project.getId(), rows)).then(importRows(STANDARD, defaults))
                    .then(Mono.defer(() -> { lockedStandard.setSeedVersion(version); return libraries.updateById(STANDARD, lockedStandard); }))
                    .thenReturn(true);
            });
        })));
    }

    static void classifyExisting(ImportResult result, String code, String name, PropertyCatalogItemEntity old) {
        if (name.equals(old.getName())) result.setSkipped(result.getSkipped() + 1);
        else result.getConflicts().add(new Conflict(code, name, old.getName()));
    }

    public static List<String> rowErrors(ImportRow row) {
        List<String> errors = new ArrayList<>();
        try { code(row.getCode()); } catch (ValidationException e) { errors.add(e.getMessage()); }
        try { requireText(row.getName(), "采集项名称", 64); } catch (ValidationException e) { errors.add(e.getMessage()); }
        try { pathSegments(row.getCategoryPath()); } catch (ValidationException e) { errors.add(e.getMessage()); }
        try { length(row.getRemark(), "备注", 1024); } catch (ValidationException e) { errors.add(e.getMessage()); }
        return errors;
    }

    private static void validateRows(List<ImportRow> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_IMPORT_ROWS) throw invalid("请选择 1 至 10000 行有效采集项");
        Set<String> codes = new HashSet<>();
        for (ImportRow row : rows) {
            if (row == null) throw invalid("导入行不能为空");
            List<String> errors = rowErrors(row);
            if (!errors.isEmpty()) throw invalid("第 " + row.getRowNumber() + " 行：" + String.join("；", errors));
            if (!codes.add(trim(row.getCode()))) throw invalid("导入列表内采集项 ID 重复：" + trim(row.getCode()));
        }
    }

    private static List<String> pathSegments(String path) {
        if (trim(path).isEmpty()) return List.of();
        List<String> parts = Arrays.stream(path.split("/", -1)).map(String::trim).collect(Collectors.toList());
        if (parts.size() > 8) throw invalid("分类最多支持 8 级");
        for (String part : parts) requireText(part, "分类路径中的名称", 128);
        return parts;
    }

    static void validateParent(String id, String parentId, List<PropertyCategoryEntity> all) {
        Map<String, PropertyCategoryEntity> byId = all.stream().collect(Collectors.toMap(PropertyCategoryEntity::getId, Function.identity()));
        Set<String> seen = new HashSet<>(); String current = trim(parentId); int depth = 0;
        while (!current.isEmpty()) {
            if (current.equals(id) || !seen.add(current)) throw invalid("不能将分类移动到自身或自己的子分类");
            PropertyCategoryEntity parent = byId.get(current);
            if (parent == null) throw invalid("上级分类不存在或属于其他项目库");
            if (++depth >= 8) throw invalid("分类最多支持 8 级");
            current = trim(parent.getParentId());
        }
        if (id != null && depth + subtreeDepth(id, all, new HashSet<>()) > 8) throw invalid("移动后分类超过 8 级");
    }

    private static int subtreeDepth(String id, List<PropertyCategoryEntity> all, Set<String> path) {
        if (!path.add(id)) throw invalid("分类树存在循环");
        int depth = 1;
        for (PropertyCategoryEntity child : all) if (id.equals(child.getParentId())) depth = Math.max(depth, 1 + subtreeDepth(child.getId(), all, new HashSet<>(path)));
        return depth;
    }

    private static Set<String> categorySelection(String id, boolean descendants, List<PropertyCategoryEntity> all) {
        if (trim(id).isEmpty()) return null;
        if ("__uncategorized__".equals(id)) return Set.of("");
        if (all.stream().noneMatch(v -> id.equals(v.getId()))) throw invalid("分类不存在或不属于当前项目库");
        Set<String> ids = new HashSet<>(); ids.add(id);
        if (descendants) {
            boolean changed;
            do { changed = false; for (PropertyCategoryEntity category : all) if (ids.contains(category.getParentId())) changed |= ids.add(category.getId()); } while (changed);
        }
        return ids;
    }

    private static List<PropertyCategoryEntity> tree(List<PropertyCategoryEntity> list) {
        list.sort(Comparator.comparingInt((PropertyCategoryEntity v) -> number(v.getSortIndex())).thenComparing(PropertyCategoryEntity::getId));
        Map<String, PropertyCategoryEntity> byId = list.stream().collect(Collectors.toMap(PropertyCategoryEntity::getId, Function.identity()));
        list.forEach(v -> v.setChildren(new ArrayList<>()));
        List<PropertyCategoryEntity> roots = new ArrayList<>();
        for (PropertyCategoryEntity item : list) {
            validateParent(item.getId(), item.getParentId(), list);
            if (trim(item.getParentId()).isEmpty()) roots.add(item); else byId.get(item.getParentId()).getChildren().add(item);
        }
        return roots;
    }

    private PropertyCatalogItemEntity copyItem(PropertyCatalogItemEntity item) {
        PropertyCatalogItemEntity copy = new PropertyCatalogItemEntity();
        copy.setLibraryId(item.getLibraryId()); copy.setCategoryId(item.getCategoryId()); copy.setCode(item.getCode()); copy.setName(item.getName());
        copy.setEnabled(item.getEnabled()); copy.setSortIndex(item.getSortIndex()); copy.setRemark(item.getRemark()); copy.setSource(item.getSource());
        return copy;
    }
    private Mono<PropertyLibraryEntity> lockedLibrary(String id) {
        return Mono.defer(() -> {
            requireText(id, "项目库", 64);
            // Row lock remains held until the surrounding reactive transaction commits.
            return libraries.createUpdate().set("modifyTime", System.currentTimeMillis()).where("id", id).execute()
                .flatMap(count -> count == 1 ? requireLibrary(id) : failure("项目库不存在"));
        });
    }
    private Mono<Integer> updateItem(PropertyCatalogItemEntity item) {
        var update = items.createUpdate().set(item).where("id", item.getId());
        if (item.getCategoryId() == null) update.setNull("categoryId");
        if (item.getRemark() == null) update.setNull("remark");
        return update.execute();
    }
    private <T> Mono<T> atomic(Mono<T> operation) { return transactions.transactional(operation); }
    private static int number(Integer value) { return value == null ? 0 : value; }
    private static String nextId() { return IDGenerator.SNOW_FLAKE_STRING.generate(); }
    public static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String emptyToNull(String value) { return trim(value).isEmpty() ? null : trim(value); }
    public static String code(String value) {
        String result = requireText(value, "采集项 ID / 编码", 64);
        if (!result.matches("^[a-zA-Z0-9_]+$") || result.equalsIgnoreCase("None")) throw invalid("采集项 ID 只能包含字母、数字、下划线，且不能为 None");
        return result;
    }
    private static String requireText(String value, String label, int max) {
        String result = trim(value); if (result.isEmpty()) throw invalid(label + "不能为空"); length(result, label, max); return result;
    }
    private static void length(String value, String label, int max) { if (value != null && value.length() > max) throw invalid(label + "最多 " + max + " 个字符"); }
    private static ValidationException invalid(String message) { return new ValidationException(message); }
    private static <T> Mono<T> failure(String message) { return Mono.error(invalid(message)); }
}
