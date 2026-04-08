package org.jetlinks.community.device.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.community.device.entity.ScheduledTaskEntity;
import org.jetlinks.community.device.enums.ScheduledTaskState;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ScheduledTaskService extends GenericReactiveCrudService<ScheduledTaskEntity, String> {

    public Mono<Void> toggle(String id, ScheduledTaskState state) {
        return createUpdate()
            .set(ScheduledTaskEntity::getState, state)
            .where(ScheduledTaskEntity::getId, id)
            .execute()
            .then();
    }

    public Mono<Void> toggleLog(String id, boolean logEnabled) {
        return createUpdate()
            .set(ScheduledTaskEntity::getLogEnabled, logEnabled)
            .where(ScheduledTaskEntity::getId, id)
            .execute()
            .then();
    }
}
