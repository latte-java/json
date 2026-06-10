package demo;

import module org.lattejava.json;

@JSON(strict = true)
@JSONSubtype("Bird")
public record Bird(String name) implements Pet {
}
