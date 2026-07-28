package demo;

import module org.lattejava.json;

@JSON(strict = true)
public record Strict(String id, @JSONRaw String raw) {
}
