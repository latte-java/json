package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record NoOptIn(String name, SemVer version) {
}
