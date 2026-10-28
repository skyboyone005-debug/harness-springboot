package com.harness.contract;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 极简 OpenAPI 3 模型：只做 harness 需要的事（加载、$ref 解析、列出 operation、路由匹配）。
 * 刻意不引入 swagger-parser 等重依赖，保证 speclint/specdiff 秒级启动。
 */
public final class OpenApi {

    public static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private final Map<String, Object> root;
    private final String source;

    private OpenApi(Map<String, Object> root, String source) {
        this.root = root;
        this.source = source;
    }

    public static OpenApi load(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            return parse(r, file.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public static OpenApi parse(String yaml) {
        return parse(new java.io.StringReader(yaml), "<string>");
    }

    @SuppressWarnings("unchecked")
    private static OpenApi parse(Reader r, String source) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
        if (!(doc instanceof Map)) {
            throw new IllegalArgumentException(source + ": not a YAML mapping");
        }
        Map<String, Object> m = (Map<String, Object>) doc;
        Object v = m.get("openapi");
        if (v == null || !String.valueOf(v).startsWith("3.")) {
            throw new IllegalArgumentException(source + ": only OpenAPI 3.x is supported");
        }
        return new OpenApi(m, source);
    }

    public String source() {
        return source;
    }

    public String version() {
        return String.valueOf(map(root.get("info")).getOrDefault("version", "0.0.0"));
    }

    public int majorVersion() {
        Matcher m = Pattern.compile("^v?(\\d+)").matcher(version());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 递归解析本地 $ref（#/components/...）。 */
    public Map<String, Object> deref(Object node) {
        Map<String, Object> m = map(node);
        int guard = 0;
        while (m.containsKey("$ref")) {
            String ref = String.valueOf(m.get("$ref"));
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException(source + ": only local $ref supported: " + ref);
            }
            Object cur = root;
            for (String part : ref.substring(2).split("/")) {
                cur = map(cur).get(part.replace("~1", "/").replace("~0", "~"));
                if (cur == null) {
                    throw new IllegalArgumentException(source + ": unresolved $ref " + ref);
                }
            }
            m = map(cur);
            if (++guard > 32) {
                throw new IllegalArgumentException(source + ": $ref cycle at " + ref);
            }
        }
        return m;
    }

    public List<Operation> operations() {
        List<Operation> out = new ArrayList<>();
        map(root.get("paths")).forEach((path, item) -> map(item).forEach((method, op) -> {
            if (METHODS.contains(method)) {
                out.add(new Operation(method.toUpperCase(), path, map(op)));
            }
        }));
        out.sort(Comparator.comparing(Operation::key));
        return out;
    }

    /** 路由匹配：字面量段优先于参数段（与 Spring 的匹配优先级一致）。 */
    public Optional<Operation> match(String method, String path) {
        return operations().stream()
                .filter(op -> op.method().equals(method.toUpperCase()) && op.pattern().matcher(path).matches())
                .min(Comparator.comparingInt(op -> op.path().split("\\{", -1).length));
    }

    public boolean pathExists(String path) {
        return operations().stream().anyMatch(op -> op.pattern().matcher(path).matches());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    /** 契约中的一个 operation。 */
    public record Operation(String method, String path, Map<String, Object> raw) {

        public String key() {
            return method + " " + path;
        }

        public String operationId() {
            return (String) raw.get("operationId");
        }

        public Pattern pattern() {
            StringBuilder re = new StringBuilder("^");
            for (String seg : path.split("/")) {
                if (seg.isEmpty()) {
                    continue;
                }
                re.append('/').append(seg.startsWith("{") && seg.endsWith("}") ? "[^/]+" : Pattern.quote(seg));
            }
            return Pattern.compile(re.append("/?$").toString());
        }

        public Map<String, Object> responses() {
            return map(raw.get("responses"));
        }
    }
}
