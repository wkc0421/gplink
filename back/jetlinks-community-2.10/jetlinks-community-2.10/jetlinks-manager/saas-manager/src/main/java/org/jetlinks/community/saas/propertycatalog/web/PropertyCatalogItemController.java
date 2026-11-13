package org.jetlinks.community.saas.propertycatalog.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.community.saas.propertycatalog.dto.CatalogRequests.*;
import org.jetlinks.community.saas.propertycatalog.entity.PropertyCatalogItemEntity;
import org.jetlinks.community.saas.propertycatalog.service.PropertyCatalogService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/device/property-library-item")
@Authorize
@Resource(id = "property-library", name = "采集项库")
@RequiredArgsConstructor
public class PropertyCatalogItemController {
    private final PropertyCatalogService service;
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<PropertyCatalogItemEntity>> query(@RequestBody ItemQuery input) { return service.queryItems(input); }
    @PostMapping @SaveAction
    public Mono<PropertyCatalogItemEntity> create(@RequestBody PropertyCatalogItemEntity input) { return service.saveItem(null, input); }
    @PutMapping("/{id}") @SaveAction
    public Mono<PropertyCatalogItemEntity> update(@PathVariable String id, @RequestBody PropertyCatalogItemEntity input) { return service.saveItem(id, input); }
    @DeleteMapping("/{id}") @DeleteAction
    public Mono<Boolean> delete(@PathVariable String id) { return service.deleteItem(id); }
    @PostMapping("/_batch-category") @SaveAction
    public Mono<Integer> batchCategory(@RequestBody BatchCategory input) { return service.batchCategory(input); }
}
