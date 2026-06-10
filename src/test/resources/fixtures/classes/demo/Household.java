package demo;

import module org.lattejava.json;

@JSON
public record Household(String name, Point origin) {
}
