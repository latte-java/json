package demo;

import module org.lattejava.json;

@JSON
public record OnlyRaw(@JSONRaw String raw) {
}
