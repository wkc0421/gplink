package org.jetlinks.community.saas.propertycatalog.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.community.saas.propertycatalog.entity.PropertyLibraryEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class PropertyLibraryStore extends GenericReactiveCrudService<PropertyLibraryEntity, String> {
    public Flux<PropertyLibraryEntity> all() { return createQuery().fetch(); }
}
