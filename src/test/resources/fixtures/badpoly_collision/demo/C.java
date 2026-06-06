package demo;

import module org.lattejava.json;

@JSON
public record C(String kind, String name) implements Base {
}
