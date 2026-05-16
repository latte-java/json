package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Lists(List<String> tags, List<Integer> nums, List<UUID> ids) {
}
