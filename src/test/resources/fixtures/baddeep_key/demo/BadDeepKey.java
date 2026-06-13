package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadDeepKey(Map<String, Map<Integer, String>> byNumber) {
}
