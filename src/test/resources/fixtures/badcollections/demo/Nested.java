package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Nested(List<List<String>> deep) {
}
