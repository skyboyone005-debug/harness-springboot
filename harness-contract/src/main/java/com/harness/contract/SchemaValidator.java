package com.harness.contract;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.0 Schema 子集校验器（覆盖本项目约定用到的关键字）：
 * type / nullable / required / properties / additionalProperties:false / items / enum /
 * minLength / maxLength / minimum / maximum / minItems / format(date-time, email)。
 * 值为 Jackson 解析出的 Map/List/String/Number/Boolean/null。
 */
public final class SchemaValidator {

    private final OpenApi api;

    public SchemaValidator(OpenApi api) {
        this.api = api;
    }

    public List<String> validate(Object value, Object schema) {
        List<String> errors = new ArrayList<>();
        check(value, schema, "$", errors, 0);
        return errors;
    }

    private void check(Object v, Object schemaNode, String at, List<String> errs, int depth) {
        if (depth > 32) {
            return;
        }
        Map<String, Object> s = api.deref(schemaNode);
        if (s.isEmpty()) {
            return; // 无约束
        }
        if (v == null) {
            if (!Boolean.TRUE.equals(s.get("nullable"))) {
                errs.add(at + ": must not be null");
            }
            return;
        }
        Object enums = s.get("enum");
        if (enums instanceof List<?> allowed && allowed.stream().noneMatch(a -> String.valueOf(a).equals(String.valueOf(v)))) {
            errs.add(at + ": " + v + " is not one of " + allowed);
        }
        String type = (String) s.get("type");
        if (type == null) {
            type = s.containsKey("properties") ? "object" : null;
        }
        if (type == null) {
            return;
        }
        switch (type) {
            case "object" -> object(v, s, at, errs, depth);
            case "array" -> array(v, s, at, errs, depth);
            case "string" -> string(v, s, at, errs);
            case "integer" -> number(v, s, at, errs, true);
            case "number" -> number(v, s, at, errs, false);
            case "boolean" -> {
                if (!(v instanceof Boolean)) {
                    errs.add(at + ": expected boolean, got " + kind(v));
                }
            }
            default -> errs.add(at + ": unsupported schema type " + type);
        }
    }

    private void object(Object v, Map<String, Object> s, String at, List<String> errs, int depth) {
        if (!(v instanceof Map<?, ?> obj)) {
            errs.add(at + ": expected object, got " + kind(v));
            return;
        }
        Map<String, Object> props = OpenApi.map(s.get("properties"));
        for (Object req : OpenApi.list(s.get("required"))) {
            if (!obj.containsKey(req)) {
                errs.add(at + ": required property '" + req + "' is missing");
            }
        }
        for (Map.Entry<?, ?> e : obj.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (props.containsKey(name)) {
                check(e.getValue(), props.get(name), at + "." + name, errs, depth + 1);
            } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                errs.add(at + ": property '" + name + "' is not declared in the contract");
            }
        }
    }

    private void array(Object v, Map<String, Object> s, String at, List<String> errs, int depth) {
        if (!(v instanceof List<?> arr)) {
            errs.add(at + ": expected array, got " + kind(v));
            return;
        }
        if (s.get("minItems") instanceof Number min && arr.size() < min.intValue()) {
            errs.add(at + ": expected at least " + min + " items");
        }
        for (int i = 0; i < arr.size(); i++) {
            check(arr.get(i), s.get("items"), at + "[" + i + "]", errs, depth + 1);
        }
    }

    private void string(Object v, Map<String, Object> s, String at, List<String> errs) {
        if (!(v instanceof String str)) {
            errs.add(at + ": expected string, got " + kind(v));
            return;
        }
        if (s.get("minLength") instanceof Number n && str.length() < n.intValue()) {
            errs.add(at + ": shorter than minLength " + n);
        }
        if (s.get("maxLength") instanceof Number n && str.length() > n.intValue()) {
            errs.add(at + ": longer than maxLength " + n);
        }
        String format = (String) s.get("format");
        if ("date-time".equals(format)) {
            try {
                OffsetDateTime.parse(str);
            } catch (DateTimeParseException e) {
                errs.add(at + ": '" + str + "' is not RFC3339 date-time");
            }
        } else if ("email".equals(format) && !str.matches("^[^@\\s]+@[^@\\s]+$")) {
            errs.add(at + ": '" + str + "' is not an email");
        }
    }

    private void number(Object v, Map<String, Object> s, String at, List<String> errs, boolean integer) {
        if (!(v instanceof Number n)) {
            errs.add(at + ": expected " + (integer ? "integer" : "number") + ", got " + kind(v));
            return;
        }
        BigDecimal d = new BigDecimal(n.toString());
        if (integer && d.stripTrailingZeros().scale() > 0) {
            errs.add(at + ": expected integer, got " + n);
        }
        if (s.get("minimum") instanceof Number min && d.compareTo(new BigDecimal(min.toString())) < 0) {
            errs.add(at + ": " + n + " < minimum " + min);
        }
        if (s.get("maximum") instanceof Number max && d.compareTo(new BigDecimal(max.toString())) > 0) {
            errs.add(at + ": " + n + " > maximum " + max);
        }
    }

    private static String kind(Object v) {
        if (v instanceof Map) {
            return "object";
        }
        if (v instanceof List) {
            return "array";
        }
        if (v instanceof Number) {
            return "number";
        }
        return v.getClass().getSimpleName().toLowerCase();
    }
}
