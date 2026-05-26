package demo;

import module java.base;
import module org.lattejava.json;

/**
 * Demonstrates the nested-object boundary: {@code home} is a nested @JSON record and {@code prior} is a collection
 * of nested records. Both are unsupported, so the processor rejects this record at compile time and emits no
 * PersonJSON companion.
 */
@JSON
public record Person(String name, Address home, List<Address> prior) {
}
