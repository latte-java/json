package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("x")
public record Orphan(String x) {
}
