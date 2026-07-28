package demo;

import module org.lattejava.json;

@JSON
public record Two(@JSONRaw String a, @JSONRaw String b) {
}
