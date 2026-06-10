package demo;

import module java.base;
import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Snake(Map<String, Object> userPrefs) {
}
