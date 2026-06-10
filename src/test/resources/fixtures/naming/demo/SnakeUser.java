package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record SnakeUser(String userName, int packSize, String httpStatus) {
}
