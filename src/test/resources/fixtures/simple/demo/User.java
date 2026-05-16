package demo;

import module org.lattejava.json;

@JSON
public record User(String name, int age, String email) {
}
