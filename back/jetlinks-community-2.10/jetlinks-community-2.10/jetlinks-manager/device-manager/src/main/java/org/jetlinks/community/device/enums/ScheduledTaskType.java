package org.jetlinks.community.device.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.dict.Dict;
import org.hswebframework.web.dict.I18nEnumDict;

@AllArgsConstructor
@Getter
@Dict("scheduled-task-type")
public enum ScheduledTaskType implements I18nEnumDict<String> {
    READ_PROPERTY("读取属性"),
    INVOKE_FUNCTION("调用功能");

    private final String text;

    @Override
    public String getValue() {
        return name();
    }
}
