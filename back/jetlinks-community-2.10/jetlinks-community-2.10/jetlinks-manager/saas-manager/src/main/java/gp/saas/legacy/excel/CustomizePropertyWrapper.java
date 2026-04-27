package gp.saas.legacy.excel;

import gp.saas.legacy.dto.CustomizePropertyExcelInfo;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.converter.RowWrapper;

import java.util.HashMap;
import java.util.Map;

public class CustomizePropertyWrapper extends RowWrapper<CustomizePropertyExcelInfo> {

    private final Map<String, String> propertyMapping = new HashMap<>();

    public CustomizePropertyWrapper() {
        add("属性ID", "id");
        add("属性名称", "name");
        add("排序", "sortsIndex");
        add("寄存器地址", "registerAddr");
        add("寄存器数量", "registerNum");
        add("数据解析方法", "parseMethod");
        add("功能码", "functionCode");
        add("来源", "source");
        add("操作类型", "type");
        add("数据类型", "valueType");
        add("精度", "scale");
        add("单位", "unit");
        add("公式", "formula");

        add("id", "id");
        add("name", "name");
        add("sortsIndex", "sortsIndex");
        add("registerAddr", "registerAddr");
        add("registerNum", "registerNum");
        add("parseMethod", "parseMethod");
        add("functionCode", "functionCode");
        add("source", "source");
        add("type", "type");
        add("valueType", "valueType");
        add("scale", "scale");
        add("unit", "unit");
        add("formula", "formula");
    }

    private void add(String header, String property) {
        propertyMapping.put(header, property);
    }

    @Override
    protected CustomizePropertyExcelInfo newInstance() {
        return new CustomizePropertyExcelInfo();
    }

    @Override
    protected CustomizePropertyExcelInfo wrap(CustomizePropertyExcelInfo instance, Cell header, Cell dataCell) {
        String headerText = header.valueAsText().orElse("");
        String value = dataCell.valueAsText().orElse("");
        String property = propertyMapping.get(headerText);
        if (property != null) {
            instance.with(property, value);
        }
        return instance;
    }
}
