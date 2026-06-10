package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record WrongType(@JSONCatchAll Map<String, String> bad) {
}
