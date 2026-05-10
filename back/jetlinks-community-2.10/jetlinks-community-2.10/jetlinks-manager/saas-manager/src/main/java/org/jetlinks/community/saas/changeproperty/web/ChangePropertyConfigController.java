package org.jetlinks.community.saas.changeproperty.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.community.io.excel.ImportExportService;
import org.jetlinks.community.io.utils.FileUtils;
import org.jetlinks.community.saas.changeproperty.entity.ChangePropertyConfigEntity;
import org.jetlinks.community.saas.changeproperty.excel.ChangePropertyConfigExcelInfo;
import org.jetlinks.community.saas.changeproperty.excel.ChangePropertyConfigWrapper;
import org.jetlinks.community.saas.changeproperty.service.ChangePropertyConfigService;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hswebframework.reactor.excel.ReactorExcel.read;

@RestController
@RequestMapping("/api/v1/change-property/config")
@Tag(name = "Change Property Config API")
public class ChangePropertyConfigController {

    private final ChangePropertyConfigService service;
    private final ImportExportService importExportService;
    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    public ChangePropertyConfigController(ChangePropertyConfigService service,
                                          ImportExportService importExportService) {
        this.service = service;
        this.importExportService = importExportService;
    }

    @PostMapping("/_query")
    @Operation(summary = "Query change property configs")
    public Mono<PagerResult<ChangePropertyConfigEntity>> query(@RequestBody Mono<QueryParamEntity> query) {
        return query.defaultIfEmpty(new QueryParamEntity()).flatMap(service::queryPager);
    }

    @GetMapping("/{id:.+}")
    @Operation(summary = "Get change property config")
    public Mono<ChangePropertyConfigEntity> get(@PathVariable String id) {
        return service.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create or overwrite change property config")
    public Mono<String> upsert(@RequestBody ChangePropertyConfigEntity request) {
        return service.upsert(request);
    }

    @PutMapping("/{id:.+}")
    @Operation(summary = "Update change property config")
    public Mono<Boolean> update(@PathVariable String id,
                                @RequestBody ChangePropertyConfigEntity request) {
        return service.updateConfig(id, request);
    }

    @DeleteMapping("/{id:.+}")
    @Operation(summary = "Delete change property config")
    public Mono<Boolean> delete(@PathVariable String id) {
        return service.deleteConfig(id);
    }

    @PostMapping("/batch")
    @Operation(summary = "Batch create or overwrite change property configs")
    public Mono<List<String>> batch(@RequestBody List<ChangePropertyConfigEntity> requests) {
        return service.batchUpsert(requests);
    }

    @PostMapping("/by-product")
    @Operation(summary = "Create change property configs by product")
    public Mono<Map<String, Object>> byProduct(@RequestBody ChangePropertyConfigEntity request) {
        return service.createByProduct(request);
    }

    @PostMapping("/batch-delete")
    @Operation(summary = "Batch delete change property configs")
    public Mono<Boolean> batchDelete(@RequestBody List<String> ids) {
        return service.batchDelete(ids);
    }

    @PostMapping("/delete-by-query")
    @Operation(summary = "Delete change property configs by condition")
    public Mono<Map<String, Object>> deleteByQuery(@RequestBody ChangePropertyConfigEntity condition) {
        return service.deleteByCondition(condition);
    }

    @PostMapping("/cache")
    @Operation(summary = "Rebuild runtime subscriptions from database")
    public Mono<Map<String, Object>> rebuildCache() {
        return service.reloadSubscriptions();
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import change property configs from multipart file")
    public Mono<Map<String, Object>> importMultipart(@RequestPart("file") FilePart file) {
        String format = resolveFormat(file.filename());
        return Mono
            .fromCallable(() -> Files.createTempFile("change-property-config-", "." + format))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(path -> file
                .transferTo(path)
                .then(importRows(path, format))
                .doFinally(signal -> deleteQuietly(path)));
    }

    @PostMapping(value = "/import", params = "fileUrl")
    @Operation(summary = "Import change property configs from fileUrl")
    public Mono<Map<String, Object>> importByFileUrl(@RequestParam String fileUrl) {
        return importExportService
            .getInputStream(fileUrl)
            .flatMap(inputStream -> importRowsAndClose(inputStream, resolveFormat(fileUrl)));
    }

    @GetMapping("/template.{format}")
    @Operation(summary = "Download change property config import template")
    public Mono<Void> template(@PathVariable String format,
                               ServerHttpResponse response) {
        setDownloadHeader(response, "change_property_config_template.", format);
        return ReactorExcel
            .<ChangePropertyConfigExcelInfo>writer(format)
            .headers(ChangePropertyConfigExcelInfo.getTemplateHeaderMapping())
            .converter(ChangePropertyConfigExcelInfo::toMap)
            .writeBuffer(Flux.empty())
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    private Mono<Map<String, Object>> importRows(InputStream inputStream, String format) {
        ChangePropertyConfigWrapper wrapper = new ChangePropertyConfigWrapper();
        return read(inputStream, format, wrapper)
            .map(ChangePropertyConfigExcelInfo::toEntity)
            .filter(entity -> hasText(entity.getDeviceId()) && hasText(entity.getPropertyId()))
            .collectList()
            .flatMap(list -> {
                if (list.isEmpty()) {
                    return Mono.error(new ValidationException("no valid rows found"));
                }
                return service.batchUpsert(list);
            })
            .map(ids -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("count", ids.size());
                result.put("ids", ids);
                return result;
            });
    }

    private Mono<Map<String, Object>> importRows(Path path, String format) {
        return Mono
            .using(
                () -> Files.newInputStream(path),
                inputStream -> importRows(inputStream, format),
                this::closeQuietly)
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<String, Object>> importRowsAndClose(InputStream inputStream, String format) {
        return Mono.using(
            () -> inputStream,
            stream -> importRows(stream, format),
            this::closeQuietly);
    }

    private String resolveFormat(String filename) {
        String format = FileUtils.getExtension(filename);
        if (format == null || format.isBlank()) {
            return "xlsx";
        }
        return format;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void setDownloadHeader(ServerHttpResponse response, String prefix, String format) {
        String fileName = URLEncoder.encode(prefix + format, StandardCharsets.UTF_8);
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
    }

    private void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignore) {
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
        }
    }
}
