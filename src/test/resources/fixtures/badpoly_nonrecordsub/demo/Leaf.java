package demo;

import module org.lattejava.json;

@JSON
public record Leaf(String x) implements Mid {
}
