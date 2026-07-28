package demo;

import module org.lattejava.json;

@JSON
public record Envelope(String kind, Inner inner, @JSONRaw String raw) {
}
