package demo;

import module org.lattejava.json;

@JSON
public record Drawing(String title, Shape shape) {
}
