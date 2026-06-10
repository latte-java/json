package demo;

import module java.base;
import module org.lattejava.json;

@JSON(omitNulls = false)
public record Loose(@JSONCatchAll Map<String, Object> extras) {
}
