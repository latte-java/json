package demo;

import module org.lattejava.json;

@JSON
public record Owner(String name, Pet pet) {
}
