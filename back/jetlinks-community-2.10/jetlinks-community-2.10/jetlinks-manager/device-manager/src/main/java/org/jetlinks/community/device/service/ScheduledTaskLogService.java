package org.jetlinks.community.device.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.community.device.entity.ScheduledTaskLogEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ScheduledTaskLogService extends GenericReactiveCrudService<ScheduledTaskLogEntity, String> {

    public Mono<Integer> deleteByTaskId(String taskId) {
        return createDelete()
            .where(ScheduledTaskLogEntity::getTaskId, taskId)
            .execute();
    }
}
