package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Registry(Map<String, Pet> byId) {
}
