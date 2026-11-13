package org.jetlinks.community.saas.propertycatalog.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.community.saas.propertycatalog.entity.PropertyCategoryEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class PropertyCategoryStore extends GenericReactiveCrudService<PropertyCategoryEntity, String> {
    public Flux<PropertyCategoryEntity> inLibrary(String libraryId) {
        return createQuery().where(PropertyCategoryEntity::getLibraryId, libraryId).fetch();
    }
}
