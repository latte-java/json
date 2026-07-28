package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "petType")
public sealed interface Pet permits Cat, Dog {
}
