package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record HasList(String name, List<String> tags) {
}
