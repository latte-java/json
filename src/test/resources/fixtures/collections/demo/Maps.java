package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Maps(Map<String, Integer> counts, Map<UUID, String> labels, Map<String, Boolean> toggles) {
}
