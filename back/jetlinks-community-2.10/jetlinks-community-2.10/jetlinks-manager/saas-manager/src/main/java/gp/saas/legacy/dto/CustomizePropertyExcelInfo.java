package gp.saas.legacy.dto;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.poi.options.AddNormalPullDownSheetOption;
import org.hswebframework.reactor.excel.poi.options.PoiWriteOptions;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.dict.EnumDict;
import org.jetlinks.core.metadata.unit.ValueUnit;
import org.jetlinks.core.metadata.unit.ValueUnits;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
public class CustomizePropertyExcelInfo {

    @Schema(description = "property id")
    private String id;

    @Schema(description = "property name")
    private String name;

    private String sortsIndex;
    private String registerAddr;
    private String registerNum;
    private String parseMethod;
    private String functionCode;
    private String source;
    private String type;
    private String valueType;
    private String scale;
    private String unit;
    private String formula;

    private static final List<String> DATA_TYPES = Lists.newArrayList(
        "boolean", "double", "float", "int", "long", "string"
    );

    private static final Map<String, String> PARSE_METHODS = new LinkedHashMap<>();

    static {
        PARSE_METHODS.put("整形_无符号_B12", "0");
        PARSE_METHODS.put("整形_无符号_B21", "1");
        PARSE_METHODS.put("整形_无符号_B1234", "2");
        PARSE_METHODS.put("整形_无符号_B2143", "3");
        PARSE_METHODS.put("整形_无符号_B4321", "4");
        PARSE_METHODS.put("整形_无符号_B3412", "5");
        PARSE_METHODS.put("整形_有符号_B12", "6");
        PARSE_METHODS.put("整形_有符号_B21", "7");
        PARSE_METHODS.put("整形_有符号_B1234", "8");
        PARSE_METHODS.put("整形_有符号_B2143", "9");
        PARSE_METHODS.put("整形_有符号_B4321", "10");
        PARSE_METHODS.put("整形_有符号_B3412", "11");
        PARSE_METHODS.put("IEEC_浮点型_B2143", "12");
        PARSE_METHODS.put("IEEC_浮点型_B4321", "13");
        PARSE_METHODS.put("原始数据", "14");
        PARSE_METHODS.put("整形_无符号_B大端", "15");
        PARSE_METHODS.put("整形_有符号_B大端", "16");
        PARSE_METHODS.put("浮点型_无符号_B大端", "17");
        PARSE_METHODS.put("浮点型_有符号_B大端", "18");
        PARSE_METHODS.put("整形_无符号_B小端", "19");
        PARSE_METHODS.put("整形_有符号_B小端", "20");
        PARSE_METHODS.put("浮点型_无符号_B小端", "21");
        PARSE_METHODS.put("浮点型_有符号_B小端", "22");
    }

    public void with(String key, Object value) {
        FastBeanCopier.copy(Collections.singletonMap(key, value), this);
    }

    public JSONObject toPropertyJson() {
        JSONObject property = new JSONObject();
        property.put("id", id);
        property.put("name", name);
        property.put("sortsIndex", sortsIndex);

        JSONObject value = new JSONObject();
        value.put("type", valueType);
        value.put("scale", scale);
        value.put("unit", parseUnit(unit));
        property.put("valueType", value);

        JSONObject expands = new JSONObject();
        expands.put("functionCode", functionCode);
        expands.put("parseMethod", parseParseMethod(parseMethod));
        expands.put("registerAddr", registerAddr);
        expands.put("registerNum", registerNum);
        expands.put("source", SourceType.getValue(source));
        expands.put("type", Arrays.stream(String.valueOf(type == null ? "" : type).split(","))
            .filter(item -> !item.isBlank())
            .map(PropertyType::getValue)
            .collect(Collectors.toList()));
        expands.put("formula", formula);
        property.put("expands", expands);
        return property;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("sortsIndex", sortsIndex);
        map.put("registerAddr", registerAddr);
        map.put("registerNum", registerNum);
        map.put("parseMethod", parseMethod);
        map.put("functionCode", functionCode);
        map.put("source", source);
        map.put("type", type);
        map.put("valueType", valueType);
        map.put("scale", scale);
        map.put("unit", unit);
        map.put("formula", formula);
        return map;
    }

    public static Flux<CustomizePropertyExcelInfo> fromProperties(JSONArray properties) {
        if (properties == null) {
            return Flux.empty();
        }
        return Flux.fromIterable(properties)
            .map(item -> JSONObject.parseObject(JSONObject.toJSONString(item)))
            .map(CustomizePropertyExcelInfo::fromPropertyJson);
    }

    public static Flux<CustomizePropertyExcelInfo> getTemplateContentMapping(JSONArray properties) {
        if (properties == null) {
            return Flux.empty();
        }
        return Flux.fromIterable(properties)
            .map(item -> JSONObject.parseObject(JSONObject.toJSONString(item)))
            .map(CustomizePropertyExcelInfo::fromPropertyJson);
    }

    private static CustomizePropertyExcelInfo fromPropertyJson(JSONObject property) {
        CustomizePropertyExcelInfo info = new CustomizePropertyExcelInfo();
        info.setId(property.getString("id"));
        info.setName(property.getString("name"));
        info.setSortsIndex(property.getString("sortsIndex"));

        JSONObject valueType = property.getJSONObject("valueType");
        if (valueType != null) {
            info.setValueType(valueType.getString("type"));
            info.setScale(valueType.getString("scale"));
            info.setUnit(convertUnit(valueType.getString("unit")));
        }

        JSONObject expands = property.getJSONObject("expands");
        if (expands != null) {
            info.setFunctionCode(expands.getString("functionCode"));
            info.setRegisterAddr(expands.getString("registerAddr"));
            info.setRegisterNum(expands.getString("registerNum"));
            info.setParseMethod(convertMethod(expands.getString("parseMethod")));
            info.setSource(SourceType.getText(expands.getString("source")));
            info.setFormula(expands.getString("formula"));
            JSONArray types = expands.getJSONArray("type");
            if (types != null && !types.isEmpty()) {
                info.setType(types.stream()
                    .map(String::valueOf)
                    .map(PropertyType::getText)
                    .collect(Collectors.joining(",")));
            }
        }
        return info;
    }

    public static List<ExcelHeader> getTemplateHeaderMapping() {
        return List.of(
            new ExcelHeader("id", "属性ID", CellDataType.STRING),
            new ExcelHeader("name", "属性名称", CellDataType.STRING),
            new ExcelHeader("sortsIndex", "排序", CellDataType.STRING),
            new ExcelHeader("registerAddr", "寄存器地址", CellDataType.STRING),
            new ExcelHeader("registerNum", "寄存器数量", CellDataType.STRING),
            new ExcelHeader("parseMethod", "数据解析方法", CellDataType.STRING),
            new ExcelHeader("functionCode", "功能码", CellDataType.STRING),
            new ExcelHeader("source", "来源", CellDataType.STRING),
            new ExcelHeader("type", "操作类型", CellDataType.STRING),
            new ExcelHeader("valueType", "数据类型", CellDataType.STRING),
            new ExcelHeader("scale", "精度", CellDataType.STRING),
            new ExcelHeader("unit", "单位", CellDataType.STRING),
            new ExcelHeader("formula", "公式", CellDataType.STRING)
        );
    }

    public static List<ExcelOption> getTemplateOptionMapping() {
        return List.of(
            PoiWriteOptions.addNormalPullDownSheet(0, 1, AddNormalPullDownSheetOption.MAX_ROW, 8, 8, PropertyType.allToList().toArray(new String[0])),
            PoiWriteOptions.addNormalPullDownSheet(0, 1, AddNormalPullDownSheetOption.MAX_ROW, 9, 9, DATA_TYPES.toArray(new String[0])),
            PoiWriteOptions.addNormalPullDownSheet(0, 1, AddNormalPullDownSheetOption.MAX_ROW, 11, 11, allToListByUnit().toArray(new String[0])),
            PoiWriteOptions.addNormalPullDownSheet(0, 1, AddNormalPullDownSheetOption.MAX_ROW, 7, 7, SourceType.allToList().toArray(new String[0])),
            PoiWriteOptions.addNormalPullDownSheet(0, 1, AddNormalPullDownSheetOption.MAX_ROW, 5, 5, allToListByParse().toArray(new String[0]))
        );
    }

    public static List<String> allToListByUnit() {
        return ValueUnits.getAllUnit().stream().map(ValueUnit::getName).collect(Collectors.toList());
    }

    public static List<String> allToListByParse() {
        return new ArrayList<>(PARSE_METHODS.keySet());
    }

    public static String convertMethod(String value) {
        if (value == null) {
            return "";
        }
        Set<Map.Entry<String, String>> entries = PARSE_METHODS.entrySet();
        for (Map.Entry<String, String> entry : entries) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return value;
    }

    public static Integer parseParseMethod(String value) {
        String parsed = PARSE_METHODS.get(value);
        if (parsed == null || parsed.isBlank()) {
            try {
                return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return Integer.parseInt(parsed);
    }

    public static String convertUnit(String value) {
        if (value == null) {
            return "";
        }
        return ValueUnits.getAllUnit()
            .stream()
            .filter(unit -> value.equals(unit.getId()) || value.equals(unit.getSymbol()) || value.equals(unit.getName()))
            .map(ValueUnit::getName)
            .findFirst()
            .orElse(value);
    }

    public static String parseUnit(String value) {
        if (value == null) {
            return "";
        }
        return ValueUnits.getAllUnit()
            .stream()
            .filter(unit -> value.equals(unit.getName()) || value.equals(unit.getId()) || value.equals(unit.getSymbol()))
            .map(ValueUnit::getId)
            .findFirst()
            .orElse(value);
    }

    @AllArgsConstructor
    @Getter
    private enum PropertyType implements EnumDict<String> {
        read("读"),
        write("写"),
        report("上报");

        private final String text;

        @Override
        public String getValue() {
            return name();
        }

        public static String getText(String value) {
            return EnumDict.findByValue(PropertyType.class, value).map(PropertyType::getText).orElse(value);
        }

        public static String getValue(String text) {
            return EnumDict.findByText(PropertyType.class, text).map(PropertyType::getValue).orElse(text);
        }

        public static List<String> allToList() {
            return Arrays.stream(PropertyType.values()).map(PropertyType::getText).collect(Collectors.toList());
        }
    }

    @AllArgsConstructor
    @Getter
    private enum SourceType implements EnumDict<String> {
        device("设备"),
        manual("手动");

        private final String text;

        @Override
        public String getValue() {
            return name();
        }

        public static String getText(String value) {
            return EnumDict.findByValue(SourceType.class, value).map(SourceType::getText).orElse(value);
        }

        public static String getValue(String text) {
            return EnumDict.findByText(SourceType.class, text).map(SourceType::getValue).orElse(text);
        }

        public static List<String> allToList() {
            return Arrays.stream(SourceType.values()).map(SourceType::getText).collect(Collectors.toList());
        }
    }
}
