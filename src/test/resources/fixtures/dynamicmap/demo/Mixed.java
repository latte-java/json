package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Mixed(Map<String, Object> meta, @JSONCatchAll Map<String, Object> extras) {
}
