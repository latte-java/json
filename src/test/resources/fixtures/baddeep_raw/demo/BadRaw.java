package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadRaw(Map<String, List> rawList, List<List<?>> wildGrid) {
}
