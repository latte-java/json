package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("kitty")
public record Cat(String name, int lives) implements Pet {
}
