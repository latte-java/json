package demo;

import module org.lattejava.json;

@JSON
public record BadChar(@JSONField(name = "a\"b") String value) {
}
