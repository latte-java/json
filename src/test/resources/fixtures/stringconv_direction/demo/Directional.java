package demo;

import module java.base;
import module org.lattejava.json;

/**
 * A readOnly member is never deserialized, so its type's String constructor is dead code; a writeOnly member is never
 * serialized, so its toString() is. Each should only have to satisfy the half it actually uses.
 */
@JSON
public record Directional(@JSONField(asString = true, readOnly = true) OnlyToString out,
                          @JSONField(asString = true, writeOnly = true) OnlyCtor in) {
}
