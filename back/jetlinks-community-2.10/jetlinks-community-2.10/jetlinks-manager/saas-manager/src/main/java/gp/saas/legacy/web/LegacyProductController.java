/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gp.saas.legacy.web;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.CustomizePropertyExcelInfo;
import gp.saas.legacy.excel.CustomizePropertyWrapper;
import gp.saas.legacy.util.LegacyMetadataUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.io.excel.ImportExportService;
import org.jetlinks.community.io.utils.FileUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hswebframework.reactor.excel.ReactorExcel.read;

@RestController
@RequestMapping("/api/v1/product")
@Authorize(ignore = true)
@Tag(name = "Legacy Product API")
public class LegacyProductController {

    private final LocalDeviceProductService productService;

    private final DeviceRegistry registry;

    private final ImportExportService importExportService;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    public LegacyProductController(LocalDeviceProductService productService,
                                   DeviceRegistry registry,
                                   ImportExportService importExportService) {
        this.productService = productService;
        this.registry = registry;
        this.importExportService = importExportService;
    }

    @GetMapping("/{id:.+}")
    @Operation(summary = "Legacy product detail")
    public Mono<DeviceProductEntity> getProduct(@PathVariable String id) {
        return productService.findById(id);
    }

    @PostMapping("/_query")
    @Operation(summary = "Legacy product paged query")
    public Mono<PagerResult<DeviceProductEntity>> query(@RequestBody Mono<QueryParamEntity> query) {
        return query.flatMap(productService::queryPager);
    }

    @PostMapping("/_query/no-paging")
    @Operation(summary = "Legacy product query without paging")
    public Flux<DeviceProductEntity> queryNoPaging(@RequestBody Mono<QueryParamEntity> query) {
        return query.flatMapMany(productService::query);
    }

    @GetMapping("/{id:.+}/metadata")
    @Operation(summary = "Legacy product metadata")
    public Mono<JSONObject> metadata(@PathVariable String id) {
        return productService
            .findById(id)
            .map(DeviceProductEntity::getMetadata)
            .map(LegacyMetadataUtils::parseMetadata);
    }

    @GetMapping("/{id:.+}/metadata/properties")
    @Operation(summary = "Legacy product metadata properties")
    public Mono<JSONArray> metadataProperties(@PathVariable String id) {
        return productService
            .findById(id)
            .map(DeviceProductEntity::getMetadata)
            .map(LegacyMetadataUtils::getProperties);
    }

    @GetMapping("/{id:.+}/metadata/functions")
    @Operation(summary = "Legacy product metadata functions")
    public Mono<JSONArray> metadataFunctions(@PathVariable String id) {
        return productService
            .findById(id)
            .map(DeviceProductEntity::getMetadata)
            .map(LegacyMetadataUtils::getFunctions);
    }

    @PostMapping("/all/deploy")
    @Operation(summary = "Legacy deploy all products")
    public Flux<JSONObject> deployAll() {
        return productService
            .createQuery()
            .fetch()
            .flatMap(product -> productService
                .deploy(product.getId())
                .map(count -> productResult(product, count)));
    }

    @PostMapping("/all/undeploy")
    @Operation(summary = "Legacy undeploy all products")
    public Flux<Integer> undeployAll() {
        return productService
            .createQuery()
            .fetch()
            .flatMap(product -> productService.cancelDeploy(product.getId()));
    }

    private JSONObject productResult(DeviceProductEntity product, Integer count) {
        JSONObject result = new JSONObject();
        result.put("id", product.getId());
        result.put("name", product.getName());
        result.put("result", count);
        return result;
    }

    @PostMapping("/{productId:.+}/property-metadata/import")
    @Operation(summary = "Legacy product property metadata import")
    public Mono<String> importPropertyMetadata(@PathVariable String productId, @RequestParam String fileUrl) {
        CustomizePropertyWrapper wrapper = new CustomizePropertyWrapper();
        return importExportService
            .getInputStream(fileUrl)
            .flatMapMany(inputStream -> read(inputStream, FileUtils.getExtension(fileUrl), wrapper))
            .collectList()
            .flatMap(list -> updateProductPropertyMetadata(productId, list));
    }

    @PostMapping("/{productId:.+}/property-metadata/update")
    @Operation(summary = "Legacy product property metadata update")
    public Mono<String> updateProductPropertyMetadata(@PathVariable String productId,
                                                      @RequestBody List<CustomizePropertyExcelInfo> propertyList) {
        if (propertyList == null || propertyList.isEmpty()) {
            return Mono.error(new ValidationException("属性列表不能为空"));
        }
        List<JSONObject> properties = propertyList
            .stream()
            .filter(property -> property.getId() != null && !property.getId().isBlank())
            .map(CustomizePropertyExcelInfo::toPropertyJson)
            .collect(Collectors.toList());

        if (properties.isEmpty()) {
            return Mono.error(new ValidationException("有效属性列表不能为空"));
        }
        return productService
            .findById(productId)
            .flatMap(product -> {
                String newMetadata = LegacyMetadataUtils.replaceProperties(product.getMetadata(), properties);
                product.setMetadata(newMetadata);
                return productService
                    .save(product)
                    .then(registry
                              .getProduct(productId)
                              .flatMap(operator -> operator.setConfig(DeviceConfigKey.metadata, newMetadata))
                              .then())
                    .thenReturn(newMetadata);
            });
    }

    @GetMapping("/{productId:.+}/property-metadata/list")
    @Operation(summary = "Legacy product property metadata list")
    public Mono<List<CustomizePropertyExcelInfo>> getProductPropertyMetadataList(@PathVariable String productId) {
        return productService
            .findById(productId)
            .switchIfEmpty(Mono.error(new ValidationException("产品不存在")))
            .map(DeviceProductEntity::getMetadata)
            .map(LegacyMetadataUtils::getProperties)
            .flatMapMany(CustomizePropertyExcelInfo::getTemplateContentMapping)
            .collectList();
    }

    @GetMapping("/{productId:.+}/property-metadata/properties.{format}")
    @Operation(summary = "Legacy product property metadata download")
    public Mono<Void> downloadProductPropertyMetadata(@PathVariable String productId,
                                                      @PathVariable String format,
                                                      ServerHttpResponse response) {
        setDownloadHeader(response, "properties.", format);
        return getProductPropertyMetadataList(productId)
            .flatMapMany(Flux::fromIterable)
            .as(data -> writeExcel(format, data))
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    @GetMapping("/property-metadata/properties_template.{format}")
    @Operation(summary = "Legacy product property metadata template")
    public Mono<Void> downloadProductPropertyMetadataTemplate(@PathVariable String format,
                                                              ServerHttpResponse response) {
        setDownloadHeader(response, "properties_template.", format);
        return writeExcel(format, Flux.empty())
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    private Flux<byte[]> writeExcel(String format, Flux<CustomizePropertyExcelInfo> data) {
        return ReactorExcel
            .<CustomizePropertyExcelInfo>writer(format)
            .headers(CustomizePropertyExcelInfo.getTemplateHeaderMapping())
            .options(CustomizePropertyExcelInfo.getTemplateOptionMapping().toArray(new ExcelOption[0]))
            .converter(CustomizePropertyExcelInfo::toMap)
            .writeBuffer(data);
    }

    private List<ExcelHeader> propertyHeaders() {
        return CustomizePropertyExcelInfo.getTemplateHeaderMapping();
    }

    private void setDownloadHeader(ServerHttpResponse response, String prefix, String format) {
        String fileName = URLEncoder.encode(prefix + format, StandardCharsets.UTF_8);
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
    }
}
