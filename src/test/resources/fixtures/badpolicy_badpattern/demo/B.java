package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record B(@JSONField(format = "MM/dd/uuuu'") LocalDate d) {
}
