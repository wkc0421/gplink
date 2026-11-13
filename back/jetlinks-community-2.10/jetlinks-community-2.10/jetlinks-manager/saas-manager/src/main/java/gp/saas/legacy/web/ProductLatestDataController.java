package gp.saas.legacy.web;

import gp.saas.legacy.dto.ProductLatestData;
import gp.saas.legacy.service.ProductLatestDataService;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/device-instance/product/{productId}/latest-data")
@Authorize
@Resource(id = "device-instance", name = "设备实例")
public class ProductLatestDataController {

    private final ProductLatestDataService latestData;

    public ProductLatestDataController(ProductLatestDataService latestData) {
        this.latestData = latestData;
    }

    @GetMapping
    @QueryAction
    public Mono<ProductLatestData> query(@PathVariable String productId,
                                         @RequestParam(defaultValue = "0") int pageIndex,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        return latestData.query(productId, pageIndex, pageSize);
    }

    @GetMapping("/export.xlsx")
    @QueryAction
    public Mono<Void> export(@PathVariable String productId, ServerHttpResponse response) {
        return latestData.export(productId)
            .flatMap(export -> ProductLatestDataExcelWriter.write(response, productId, export.columns(), export.rows()));
    }
}
