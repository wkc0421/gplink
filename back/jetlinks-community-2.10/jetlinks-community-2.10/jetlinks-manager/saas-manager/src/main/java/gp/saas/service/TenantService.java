package gp.saas.service;

import gp.saas.entity.TenantEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;

@Service
public class TenantService extends GenericReactiveCrudService<TenantEntity, String> {
}
