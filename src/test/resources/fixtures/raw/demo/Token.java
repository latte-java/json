package demo;

import module org.lattejava.json;

@JSON
public record Token(String sub, long exp, @JSONRaw String raw) {
}
