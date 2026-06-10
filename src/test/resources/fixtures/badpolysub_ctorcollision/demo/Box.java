package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "kind")
public sealed interface Box permits BadCtor {
}
