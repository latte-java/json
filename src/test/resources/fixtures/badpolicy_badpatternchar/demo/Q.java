package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Q(@JSONField(format = "yyyy\"MM") LocalDate d) {
}
