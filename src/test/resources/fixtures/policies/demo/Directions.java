package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Directions(
    String both,
    @JSONField(readOnly = true) String readOnly,
    @JSONField(writeOnly = true) String writeOnly,
    @JSONField(ignore = true) String ignored,
    @JSONField(readOnly = true) List<String> tags) {
}
