package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.CAMEL_CASE)
public record CamelUser(String userID) {
}
