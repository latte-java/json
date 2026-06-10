package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Settings(String id, Map<String, Object> prefs) {
}
