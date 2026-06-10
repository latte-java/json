package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record WithField(@JSONCatchAll @JSONField(name = "x") Map<String, Object> m) {
}
