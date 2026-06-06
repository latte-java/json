package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record User(String name, Address address, List<Address> prior, Set<Address> seen,
                   Map<AddressType, Address> byType) {
}
