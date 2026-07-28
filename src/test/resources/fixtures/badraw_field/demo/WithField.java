package demo;

import module org.lattejava.json;

@JSON
public record WithField(String id, @JSONRaw @JSONField(name = "r") String m) {
}
