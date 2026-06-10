package demo;

import module org.lattejava.json;

@JSON
public record RW(@JSONField(readOnly = true, writeOnly = true) String x) {
}
