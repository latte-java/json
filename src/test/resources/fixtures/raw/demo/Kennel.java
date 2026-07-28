package demo;

import module org.lattejava.json;

@JSON
public record Kennel(Pet pet, @JSONRaw String raw) {
}
