package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Sets(Set<String> names, Set<Long> codes) {
}
