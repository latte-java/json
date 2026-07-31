package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record WithFormat(@JSONField(asString = true, format = "yyyy-MM-dd") LocalDate when) {
}
