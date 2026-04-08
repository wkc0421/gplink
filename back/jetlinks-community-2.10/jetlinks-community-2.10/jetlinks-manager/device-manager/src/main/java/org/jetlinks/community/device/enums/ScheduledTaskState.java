package org.jetlinks.community.device.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.dict.Dict;
import org.hswebframework.web.dict.I18nEnumDict;

@AllArgsConstructor
@Getter
@Dict("scheduled-task-state")
public enum ScheduledTaskState implements I18nEnumDict<String> {
    enabled("已启用"),
    disabled("已禁用");

    private final String text;

    @Override
    public String getValue() {
        return name();
    }
}
