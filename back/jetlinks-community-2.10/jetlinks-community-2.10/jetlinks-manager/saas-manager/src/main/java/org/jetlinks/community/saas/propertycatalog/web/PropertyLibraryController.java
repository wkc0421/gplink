package org.jetlinks.community.saas.propertycatalog.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.community.saas.propertycatalog.dto.CatalogRequests.*;
import org.jetlinks.community.saas.propertycatalog.entity.PropertyLibraryEntity;
import org.jetlinks.community.saas.propertycatalog.service.PropertyCatalogExcel;
import org.jetlinks.community.saas.propertycatalog.service.PropertyCatalogService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/device/property-library")
@Authorize
@Resource(id = "property-library", name = "采集项库")
@RequiredArgsConstructor
public class PropertyLibraryController {
    private final PropertyCatalogService service;
    private final PropertyCatalogExcel excel;
    @GetMapping("/_all") @QueryAction
    public Flux<PropertyLibraryEntity> all(@RequestParam(required = false) Boolean enabled) { return service.libraries(enabled); }
    @PostMapping @SaveAction
    public Mono<PropertyLibraryEntity> create(@RequestBody PropertyLibraryEntity input) { return service.saveLibrary(null, input); }
    @PutMapping("/{id}") @SaveAction
    public Mono<PropertyLibraryEntity> update(@PathVariable String id, @RequestBody PropertyLibraryEntity input) { return service.saveLibrary(id, input); }
    @DeleteMapping("/{id}") @DeleteAction
    public Mono<Boolean> delete(@PathVariable String id) { return service.deleteLibrary(id); }
    @PostMapping(value = "/{id}/import/_preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @SaveAction
    public Mono<Preview> preview(@PathVariable String id, @RequestPart("file") FilePart file) {
        return service.requireLibrary(id).then(DataBufferUtils.join(file.content(), 10 * 1024 * 1024))
            .switchIfEmpty(Mono.error(new ValidationException("上传文件为空")))
            .map(buffer -> { try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; } finally { DataBufferUtils.release(buffer); } })
            .publishOn(Schedulers.boundedElastic()).map(bytes -> excel.parse(bytes, file.filename()))
            .flatMap(value -> service.validatePreview(id, value));
    }
    @PostMapping("/{id}/import/_confirm") @SaveAction
    public Mono<ImportResult> confirm(@PathVariable String id, @RequestBody Confirm input) { return service.confirmImport(id, input); }
    @PostMapping("/standard/_adopt") @SaveAction
    public Mono<ImportResult> adopt(@RequestBody Adopt input) { return service.adopt(input); }
}
