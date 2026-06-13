package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadPlain(Map<String, List<Plain>> deep) {
}
