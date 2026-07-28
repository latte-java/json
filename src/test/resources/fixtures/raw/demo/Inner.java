package demo;

import module org.lattejava.json;

@JSON
public record Inner(int y, @JSONRaw String raw) {
}
