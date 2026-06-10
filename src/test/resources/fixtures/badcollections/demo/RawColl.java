package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record RawColl(List raw, Set<?> anySet) {
}
