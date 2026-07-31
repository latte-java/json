package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record HasNoToString(@JSONField(asString = true) NoToString tag) {
}
