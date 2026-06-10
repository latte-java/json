package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Stamped(@JSONField(format = "yyyy-MM-dd'T'HH:mm:ss'Z'") Instant at) {
}
