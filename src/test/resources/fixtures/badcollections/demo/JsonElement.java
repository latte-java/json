package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record JsonElement(List<Inner> items) {
}
