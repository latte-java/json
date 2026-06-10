package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Ids(UUID id, Color color) {
}
