package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("same")
public record A(String x) implements Base {
}
