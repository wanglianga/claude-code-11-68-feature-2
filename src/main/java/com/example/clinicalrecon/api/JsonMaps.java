package com.example.clinicalrecon.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 JDBC 列名（snake_case）的 Map 结果统一转为 camelCase，保持 API 输出一致。 */
final class JsonMaps {
    private JsonMaps() {}

    static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
        return rows.stream().map(JsonMaps::camel).toList();
    }

    static Map<String, Object> camel(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        row.forEach((k, v) -> out.put(toCamel(k), v));
        return out;
    }

    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
