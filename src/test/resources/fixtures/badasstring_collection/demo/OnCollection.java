package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record OnCollection(@JSONField(asString = true) List<String> tags) {
}
