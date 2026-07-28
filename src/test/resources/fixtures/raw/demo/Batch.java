package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Batch(List<Inner> items, @JSONRaw String raw) {
}
