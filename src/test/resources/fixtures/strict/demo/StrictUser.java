package demo;

import module org.lattejava.json;

@JSON(strict = true)
public record StrictUser(String name, int age) {
}
