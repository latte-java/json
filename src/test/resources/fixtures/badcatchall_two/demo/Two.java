package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Two(@JSONCatchAll Map<String, Object> a, @JSONCatchAll Map<String, Object> b) {
}
