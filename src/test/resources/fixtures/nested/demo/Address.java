package demo;

import demo.geo.Geo;
import module org.lattejava.json;

@JSON
public record Address(String street, String city, Geo geo) {
}
