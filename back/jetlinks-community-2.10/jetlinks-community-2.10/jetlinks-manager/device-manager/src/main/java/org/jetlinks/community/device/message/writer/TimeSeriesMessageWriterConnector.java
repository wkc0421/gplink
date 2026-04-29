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
package org.jetlinks.community.device.message.writer;

import lombok.Generated;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.device.service.data.DeviceDataService;
import org.jetlinks.community.gateway.annotation.Subscribe;
import org.jetlinks.community.things.data.ThingsDataWriter;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.property.PropertyMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Writes device messages to time-series storage.
 *
 * @author zhouhao
 * @since 1.0
 */
@Slf4j
public class TimeSeriesMessageWriterConnector {

    private final DeviceDataService dataService;

    private final ThingsDataWriter thingsDataWriter;

    public TimeSeriesMessageWriterConnector(DeviceDataService dataService,
                                            ThingsDataWriter thingsDataWriter) {
        this.dataService = dataService;
        this.thingsDataWriter = thingsDataWriter;
    }

    @Subscribe(topics = "/device/**", id = "device-message-ts-writer", priority = 100)
    @Generated
    public Mono<Void> writeDeviceMessageToTs(DeviceMessage message) {
        return dataService
            .saveDeviceMessage(message)
            .then(writeToThingsDataWriter(message))
            .onErrorResume(err -> {
                log.warn("write device message error {}", message, err);
                return Mono.empty();
            });
    }

    private Mono<Void> writeToThingsDataWriter(DeviceMessage message) {
        if (message instanceof PropertyMessage) {
            return Flux
                .fromIterable(((PropertyMessage) message).getCompleteProperties())
                .concatMap(prop -> thingsDataWriter
                    .updateProperty(message.getThingType(), message.getThingId(), prop))
                .then();
        }
        return Mono.empty();
    }

}
