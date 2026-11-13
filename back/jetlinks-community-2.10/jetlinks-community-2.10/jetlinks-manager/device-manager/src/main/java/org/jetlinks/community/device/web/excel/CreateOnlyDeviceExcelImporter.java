package org.jetlinks.community.device.web.excel;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.community.device.entity.DeviceProductEntity;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.io.excel.ImportHelper;
import org.jetlinks.community.io.file.FileManager;
import org.jetlinks.core.metadata.ConfigPropertyMetadata;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/** Writes each validated row before the importer records its result in the download. */
public class CreateOnlyDeviceExcelImporter extends DeviceExcelImporter {
    private final Function<DeviceExcelInfo, Mono<Void>> insert;

    public CreateOnlyDeviceExcelImporter(FileManager files, WebClient client,
                                         DeviceProductEntity product, List<ConfigPropertyMetadata> configs,
                                         LocalDeviceInstanceService devices, Authentication auth,
                                         Function<DeviceExcelInfo, Mono<Void>> insert) {
        super(files, client, product, configs, devices, auth);
        this.insert = insert;
    }

    @Override
    protected Mono<Void> handleData(Flux<DeviceExcelInfo> data) {
        return data.concatMap(row -> super.handleData(Flux.just(row))
                                         .then(Mono.defer(() -> insert.apply(row))))
                   .then();
    }

    @Override
    protected void customImport(ImportHelper<DeviceExcelInfo> helper) {
        super.customImport(helper);
        // No batch fallback: it could replay committed inserts and misreport them as duplicates.
        helper.bufferSize(1).fallbackSingle(false);
    }
}
