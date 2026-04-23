package gp.saas.web;

import gp.saas.entity.TenantEntity;
import gp.saas.service.TenantService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/saas/tenant")
@Resource(id = "saas-tenant", name = "SaaS租户管理")
@Tag(name = "SaaS管理")
public class TenantController implements ReactiveServiceCrudController<TenantEntity, String> {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    public TenantService getService() {
        return tenantService;
    }
}
