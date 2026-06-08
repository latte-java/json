package demo;

import module org.lattejava.json;

@JSON
public record Ig(@JSONField(ignore = true, name = "x") String value) {
}
