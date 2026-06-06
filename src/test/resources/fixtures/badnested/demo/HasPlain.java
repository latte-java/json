package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record HasPlain(Plain p, List<Plain> ps) {
}
