package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.KEBAB_CASE)
public record Inner(String innerField) {
}
