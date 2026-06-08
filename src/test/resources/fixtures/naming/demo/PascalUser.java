package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.PASCAL_CASE)
public record PascalUser(String userName) {
}
