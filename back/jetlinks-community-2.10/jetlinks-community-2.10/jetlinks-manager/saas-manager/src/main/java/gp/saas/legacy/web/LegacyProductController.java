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
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.io.excel.ImportExportService;
import org.jetlinks.community.io.file.FileManager;
import org.jetlinks.community.io.utils.FileUtils;
import org.jetlinks.community.protocol.ProtocolSupportEntity;
import org.jetlinks.community.protocol.service.LocalProtocolSupportService;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

import static org.hswebframework.reactor.excel.ReactorExcel.read;

@RestController
@RequestMapping({
    "/v1/product",
    "/api/v1/product"
})
@Authorize
@Resource(id = "device-product", name = "Device product")
@Tag(name = "Legacy Product API")
public class LegacyProductController {

    private final LocalDeviceProductService productService;

    private final LocalProtocolSupportService protocolService;

    private final DeviceRegistry registry;

    private final ImportExportService importExportService;

    private final FileManager fileManager;

    private final WebClient.Builder webClientBuilder;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    private static final Pattern INTERNAL_FILE_PATH = Pattern.compile("/file/([^/?#]+)");

    public LegacyProductController(LocalDeviceProductService productService,
                                   LocalProtocolSupportService protocolService,
                                   DeviceRegistry registry,
                                   ImportExportService importExportService,
                                   FileManager fileManager,
                                   WebClient.Builder webClientBuilder) {
        this.productService = productService;
        this.protocolService = protocolService;
        this.registry = registry;
        this.importExportService = importExportService;
        this.fileManager = fileManager;
        this.webClientBuilder = webClientBuilder;
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

    @GetMapping("/protocols/download")
    @Operation(summary = "Download all product protocol JARs")
    public Mono<Void> downloadAllProductProtocols(ServerHttpResponse response) {
        return Mono
            .zip(productService.createQuery().fetch().collectList(),
                 protocolService.createQuery()
                               .fetch()
                               .collectMap(ProtocolSupportEntity::getId))
            .flatMap(data -> Mono.usingWhen(
                Mono.fromCallable(() -> Files.createTempFile("product-protocols-", ".zip"))
                    .subscribeOn(Schedulers.boundedElastic()),
                zipPath -> writeProductProtocolZip(data.getT1(), data.getT2(), zipPath)
                    .flatMap(count -> streamZip(zipPath, response)),
                zipPath -> Mono
                    .fromRunnable(() -> deleteTempFile(zipPath))
                    .subscribeOn(Schedulers.boundedElastic())
            ));
    }

    private Mono<Integer> writeProductProtocolZip(List<DeviceProductEntity> products,
                                                  Map<String, ProtocolSupportEntity> protocols,
                                                  Path zipPath) {
        return Mono.using(
            () -> new ZipOutputStream(Files.newOutputStream(zipPath)),
            zip -> Flux
                .fromIterable(products)
                .concatMap(product -> writeProductProtocol(product, protocols, zip))
                .reduce(0, Integer::sum)
                .flatMap(count -> {
                    if (count == 0) {
                        return Mono.error(new BusinessException("error.product_protocol_jar_not_found", 404));
                    }
                    return Mono.fromRunnable(() -> finishZip(zip))
                               .subscribeOn(Schedulers.boundedElastic())
                               .thenReturn(count);
                }),
            this::closeZip
        ).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Integer> writeProductProtocol(DeviceProductEntity product,
                                                Map<String, ProtocolSupportEntity> protocols,
                                                ZipOutputStream zip) {
        String protocolId = product.getMessageProtocol();
        if (!hasText(protocolId)) {
            return Mono.just(0);
        }

        ProtocolSupportEntity protocol = protocols.get(protocolId);
        // Built-in protocols do not have a downloadable product-specific JAR.
        if (protocol == null || !"jar".equalsIgnoreCase(protocol.getType())) {
            return Mono.just(0);
        }

        String productId = product.getId();
        if (!isSafeZipEntryName(productId)) {
            return Mono.error(new BusinessException("error.product_id_invalid", 400, productId));
        }

        AtomicLong contentLength = new AtomicLong();
        return Mono
            .fromRunnable(() -> putZipEntry(zip, productId))
            .subscribeOn(Schedulers.boundedElastic())
            .thenMany(readProtocolContent(protocol)
                          .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                          .concatMap(buffer -> writeDataBuffer(zip, buffer, contentLength)))
            .then(Mono.defer(() -> {
                if (contentLength.get() == 0) {
                    return Mono.error(new BusinessException("error.product_protocol_jar_empty", 400, productId));
                }
                return Mono
                    .fromRunnable(() -> closeZipEntry(zip))
                    .subscribeOn(Schedulers.boundedElastic());
            }))
            .thenReturn(1);
    }

    private Flux<DataBuffer> readProtocolContent(ProtocolSupportEntity protocol) {
        Map<String, Object> configuration = protocol.getConfiguration();
        String location = valueOf(configuration, "location");
        Set<String> fileIds = new LinkedHashSet<>();
        String configuredFileId = valueOf(configuration, "fileId");
        if (hasText(configuredFileId)) {
            fileIds.add(configuredFileId);
        }
        String locationFileId = extractFileId(location);
        if (hasText(locationFileId)) {
            fileIds.add(locationFileId);
        }

        return Flux
            .fromIterable(fileIds)
            .concatMap(fileManager::getFile)
            .next()
            .flatMapMany(file -> fileManager.read(file.getId()))
            .switchIfEmpty(Flux.defer(() -> {
                if (!isHttpUrl(location)) {
                    return Flux.error(new BusinessException("error.product_protocol_jar_source_invalid", 400));
                }
                return readRemoteProtocol(location);
            }));
    }

    private Flux<DataBuffer> readRemoteProtocol(String location) {
        return webClientBuilder
            .build()
            .get()
            .uri(URI.create(location))
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .exchangeToMono(remote -> {
                if (!remote.statusCode().is2xxSuccessful()) {
                    return remote
                        .releaseBody()
                        .then(Mono.error(new BusinessException("error.product_protocol_jar_remote_failed", 502)));
                }
                return Mono.just(remote.bodyToFlux(DataBuffer.class));
            })
            .flatMapMany(body -> body);
    }

    private Mono<Void> streamZip(Path zipPath, ServerHttpResponse response) {
        return Mono
            .fromCallable(() -> Files.size(zipPath))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(size -> {
                response.getHeaders().setContentType(MediaType.parseMediaType("application/zip"));
                response.getHeaders().setContentDisposition(ContentDisposition
                                                                 .attachment()
                                                                 .filename("product-protocols.zip", StandardCharsets.UTF_8)
                                                                 .build());
                response.getHeaders().setContentLength(size);
                return response.writeWith(DataBufferUtils.read(zipPath, bufferFactory, 8192));
            });
    }

    private Mono<Void> writeDataBuffer(ZipOutputStream zip,
                                       DataBuffer buffer,
                                       AtomicLong contentLength) {
        return Mono
            .fromRunnable(() -> writeDataBufferContent(zip, buffer, contentLength))
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> DataBufferUtils.release(buffer))
            .then();
    }

    private void putZipEntry(ZipOutputStream zip, String productId) {
        try {
            zip.putNextEntry(new ZipEntry(productId + ".jar"));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeDataBufferContent(ZipOutputStream zip,
                                        DataBuffer buffer,
                                        AtomicLong contentLength) {
        try {
            int length = buffer.readableByteCount();
            byte[] content = new byte[length];
            buffer.read(content);
            zip.write(content);
            contentLength.addAndGet(length);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void closeZipEntry(ZipOutputStream zip) {
        try {
            zip.closeEntry();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void finishZip(ZipOutputStream zip) {
        try {
            zip.finish();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void closeZip(ZipOutputStream zip) {
        try {
            zip.close();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String valueOf(Map<String, Object> configuration, String key) {
        if (configuration == null) {
            return null;
        }
        Object value = configuration.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String extractFileId(String location) {
        if (!hasText(location)) {
            return null;
        }
        var matcher = INTERNAL_FILE_PATH.matcher(location);
        if (!matcher.find()) {
            return null;
        }
        String fileId = matcher.group(1);
        int dot = fileId.indexOf('.');
        return dot > 0 ? fileId.substring(0, dot) : fileId;
    }

    private boolean isHttpUrl(String location) {
        if (!hasText(location)) {
            return false;
        }
        try {
            String scheme = URI.create(location).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSafeZipEntryName(String productId) {
        return hasText(productId)
            && !productId.contains("/")
            && !productId.contains("\\")
            && productId.chars().noneMatch(Character::isISOControl);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
                String newMetadata = LegacyMetadataUtils.replaceProperties(
                    product.getMetadata(), LegacyMetadataUtils.completePropertySortIndexes(properties));
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
