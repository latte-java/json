package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record AlreadySupported(@JSONField(asString = true) UUID id) {
}
