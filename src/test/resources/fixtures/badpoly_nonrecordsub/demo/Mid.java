package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "m")
public sealed interface Mid extends Base permits Leaf {
}
