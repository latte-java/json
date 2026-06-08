package demo;

import module org.lattejava.json;

@JSON
public record Sub(@JSONField(name = "kind") String label, String name) implements Base {
}
