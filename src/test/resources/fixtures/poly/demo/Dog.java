package demo;

import module org.lattejava.json;

@JSON
public record Dog(String name, int packSize) implements Pet {
}
