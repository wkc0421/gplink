package org.jetlinks.community.saas.propertycatalog.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.community.saas.propertycatalog.entity.PropertyCategoryEntity;
import org.jetlinks.community.saas.propertycatalog.service.PropertyCatalogService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/device/property-library-category")
@Authorize
@Resource(id = "property-library", name = "采集项库")
@RequiredArgsConstructor
public class PropertyCategoryController {
    private final PropertyCatalogService service;
    @GetMapping("/_tree") @QueryAction
    public Flux<PropertyCategoryEntity> tree(@RequestParam String libraryId) { return service.categoryTree(libraryId); }
    @PostMapping @SaveAction
    public Mono<PropertyCategoryEntity> create(@RequestBody PropertyCategoryEntity input) { return service.saveCategory(null, input); }
    @PutMapping("/{id}") @SaveAction
    public Mono<PropertyCategoryEntity> update(@PathVariable String id, @RequestBody PropertyCategoryEntity input) { return service.saveCategory(id, input); }
    @DeleteMapping("/{id}") @DeleteAction
    public Mono<Boolean> delete(@PathVariable String id) { return service.deleteCategory(id); }
}
