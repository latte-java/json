package demo;

import module org.lattejava.json;

@JSON
public record Dup(@JSONField(name = "id") String first, @JSONField(name = "id") String second) {
}
