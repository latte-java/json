package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("same")
public record B(String y) implements Base {
}
