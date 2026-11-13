package org.jetlinks.community.saas.propertycatalog.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.community.saas.propertycatalog.entity.PropertyCatalogItemEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class PropertyCatalogItemStore extends GenericReactiveCrudService<PropertyCatalogItemEntity, String> {
    public Flux<PropertyCatalogItemEntity> inLibrary(String libraryId) {
        return createQuery().where(PropertyCatalogItemEntity::getLibraryId, libraryId).fetch();
    }
}
