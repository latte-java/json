package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Response(String id, int code, @JSONCatchAll Map<String, Object> extras) {
}
