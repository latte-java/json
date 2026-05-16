package demo;

import module org.lattejava.json;

@JSON
public record Primitives(boolean flag, byte b, short s, int i, long l,
                         float f, double d, Integer boxedInt, Long boxedLong) {
}
