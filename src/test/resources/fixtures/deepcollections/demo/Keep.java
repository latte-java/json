package demo;

import module java.base;
import module org.lattejava.json;

@JSON(omitNulls = false)
public record Keep(Map<String, List<Integer>> data) {
}
