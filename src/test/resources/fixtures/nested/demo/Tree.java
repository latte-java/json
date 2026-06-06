package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Tree(String name, List<Tree> kids) {
}
