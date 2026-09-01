package gp.saas.apitoken.service;

import gp.saas.apitoken.entity.ApiTokenAuditEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;

@Service
public class ApiTokenAuditService extends GenericReactiveCrudService<ApiTokenAuditEntity, String> {
}
