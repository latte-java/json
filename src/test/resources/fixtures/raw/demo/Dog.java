package demo;

import module org.lattejava.json;

@JSON
public record Dog(String name, @JSONRaw String raw) implements Pet {
}
