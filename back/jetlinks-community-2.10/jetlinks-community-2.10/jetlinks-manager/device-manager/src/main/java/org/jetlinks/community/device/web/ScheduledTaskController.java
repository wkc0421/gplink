package org.jetlinks.community.device.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.jetlinks.community.device.entity.ScheduledTaskEntity;
import org.jetlinks.community.device.entity.ScheduledTaskLogEntity;
import org.jetlinks.community.device.enums.ScheduledTaskState;
import org.jetlinks.community.device.service.ScheduledTaskLogService;
import org.jetlinks.community.device.service.ScheduledTaskService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/scheduled-task")
@Resource(id = "scheduled-task", name = "定时任务")
@Authorize
@Tag(name = "定时任务接口")
@AllArgsConstructor
public class ScheduledTaskController implements ReactiveServiceCrudController<ScheduledTaskEntity, String> {

    @Getter
    private final ScheduledTaskService service;

    private final ScheduledTaskLogService logService;

    @PutMapping("/{id}/_enable")
    @SaveAction
    @Operation(summary = "启用定时任务")
    public Mono<Void> enable(@PathVariable @Parameter(description = "任务ID") String id) {
        return service.toggle(id, ScheduledTaskState.enabled);
    }

    @PutMapping("/{id}/_disable")
    @SaveAction
    @Operation(summary = "禁用定时任务")
    public Mono<Void> disable(@PathVariable @Parameter(description = "任务ID") String id) {
        return service.toggle(id, ScheduledTaskState.disabled);
    }

    @PutMapping("/{id}/_log/enable")
    @SaveAction
    @Operation(summary = "开启执行日志")
    public Mono<Void> enableLog(@PathVariable @Parameter(description = "任务ID") String id) {
        return service.toggleLog(id, true);
    }

    @PutMapping("/{id}/_log/disable")
    @SaveAction
    @Operation(summary = "关闭执行日志")
    public Mono<Void> disableLog(@PathVariable @Parameter(description = "任务ID") String id) {
        return service.toggleLog(id, false);
    }

    @PostMapping("/{taskId}/logs/_query")
    @QueryAction
    @Operation(summary = "分页查询定时任务执行日志")
    public Mono<PagerResult<ScheduledTaskLogEntity>> queryLogs(
        @PathVariable @Parameter(description = "任务ID") String taskId,
        @RequestBody Mono<QueryParamEntity> query) {
        return query.flatMap(param -> {
            param.toNestQuery(q -> q.is(ScheduledTaskLogEntity::getTaskId, taskId));
            return logService.queryPager(param);
        });
    }

    @DeleteMapping("/{taskId}/logs")
    @DeleteAction
    @Operation(summary = "删除定时任务执行日志")
    public Mono<Integer> deleteLogs(@PathVariable @Parameter(description = "任务ID") String taskId) {
        return logService.deleteByTaskId(taskId);
    }
}
