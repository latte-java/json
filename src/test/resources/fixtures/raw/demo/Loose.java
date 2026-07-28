package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Loose(String id, @JSONCatchAll Map<String, Object> extras, @JSONRaw String raw) {
}
