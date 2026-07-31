package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record HasNoCtor(@JSONField(asString = true) NoCtor tag) {
}
