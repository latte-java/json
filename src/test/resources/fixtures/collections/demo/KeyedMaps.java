package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record KeyedMaps(Map<Color, Integer> byColor, Map<Instant, String> byTime) {
}
