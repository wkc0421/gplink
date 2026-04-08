package org.jetlinks.community.device.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.dict.Dict;
import org.hswebframework.web.dict.I18nEnumDict;

@AllArgsConstructor
@Getter
@Dict("log-status")
public enum LogStatus implements I18nEnumDict<String> {
    SUCCESS("成功"),
    FAILED("失败");

    private final String text;

    @Override
    public String getValue() {
        return name();
    }
}
