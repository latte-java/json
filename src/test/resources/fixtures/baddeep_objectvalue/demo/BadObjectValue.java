package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadObjectValue(Map<String, Map<String, Object>> nestedDynamic, Map<String, List<Object>> anyList) {
}
