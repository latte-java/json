package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Renamed(String userName, @JSONField(name = "X-Request-ID") String requestId,
                      @JSONField(name = "") String fallBack) {
}
