package demo;

import module org.lattejava.json;

@JSON
public record WithCatchAll(String id, @JSONRaw @JSONCatchAll String m) {
}
