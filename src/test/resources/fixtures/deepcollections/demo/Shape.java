package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "type")
public sealed interface Shape permits Circle, Square {
}
