package demo;

import module org.lattejava.json;

@JSON(omitNulls = false)
public record Loose(String name, Address address) {
}
