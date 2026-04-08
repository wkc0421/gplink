package org.jetlinks.community.device.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.dict.Dict;
import org.hswebframework.web.dict.I18nEnumDict;

@AllArgsConstructor
@Getter
@Dict("execution-mode")
public enum ExecutionMode implements I18nEnumDict<String> {
    PARALLEL("并行"),
    SERIAL("串行");

    private final String text;

    @Override
    public String getValue() {
        return name();
    }
}
