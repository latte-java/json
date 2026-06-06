package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "t")
public sealed interface Base permits Impl {
}
