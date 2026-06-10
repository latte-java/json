package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("circle")
public record Circle(int radius) implements Shape {
}
