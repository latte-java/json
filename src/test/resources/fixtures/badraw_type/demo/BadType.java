package demo;

import module org.lattejava.json;

@JSON
public record BadType(String id, @JSONRaw int bad) {
}
