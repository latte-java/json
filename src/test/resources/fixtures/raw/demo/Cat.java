package demo;

import module org.lattejava.json;

@JSON
public record Cat(String name) implements Pet {
}
