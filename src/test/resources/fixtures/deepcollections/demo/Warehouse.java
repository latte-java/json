package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Warehouse(Map<Region, Set<Product>> stock,
                        Map<String, Map<String, Product>> index,
                        Map<String, List<Map<Instant, Integer>>> series,
                        Map<String, List<Shape>> shapes) {
}
