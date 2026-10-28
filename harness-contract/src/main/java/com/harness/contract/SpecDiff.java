package com.harness.contract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * specdiff：比较两版契约，输出面向前端的变更日志；存在破坏性变更（breaking change）时退出码为 1。
 * 只有 info.version 主版本号提升时，破坏性变更才被放行。
 *
 * <pre>java -cp ... com.harness.contract.SpecDiff base.yaml head.yaml</pre>
 */
public final class SpecDiff {

    public record Change(boolean breaking, String op, String msg) {
    }

    private record Field(String type, boolean required) {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: SpecDiff <base.yaml> <head.yaml>");
            System.exit(2);
        }
        OpenApi base = OpenApi.load(Path.of(args[0]));
        OpenApi head = OpenApi.load(Path.of(args[1]));
        List<Change> changes = diff(base, head);
        long breaking = changes.stream().filter(Change::breaking).count();
        for (Change c : changes) {
            System.out.printf("  %-16s %-28s %s%n", c.breaking() ? "[BREAKING]" : "[added/changed]", c.op(), c.msg());
        }
        if (changes.isEmpty()) {
            System.out.println("  (no API changes)");
        }
        if (breaking > 0) {
            if (head.majorVersion() > base.majorVersion()) {
                System.out.printf("  %d breaking change(s) allowed: major version bumped %s -> %s%n", breaking, base.version(), head.version());
                return;
            }
            System.err.printf("specdiff: %d breaking change(s). 要么改成向后兼容（新增可选字段/新版本路径），要么提升 info.version 主版本并通知前端。%n", breaking);
            System.exit(1);
        }
    }

    public static List<Change> diff(OpenApi base, OpenApi head) {
        List<Change> out = new ArrayList<>();
        Map<String, OpenApi.Operation> b = index(base), h = index(head);
        b.keySet().stream().filter(k -> !h.containsKey(k)).forEach(k -> out.add(new Change(true, k, "operation removed")));
        h.forEach((k, hop) -> {
            OpenApi.Operation bop = b.get(k);
            if (bop == null) {
                out.add(new Change(false, k, "operation added (" + hop.operationId() + ")"));
            } else {
                diffOp(k, base, bop, head, hop, out);
            }
        });
        return out;
    }

    private static Map<String, OpenApi.Operation> index(OpenApi api) {
        Map<String, OpenApi.Operation> m = new TreeMap<>();
        api.operations().forEach(op -> m.put(op.key(), op));
        return m;
    }

    private static void diffOp(String k, OpenApi base, OpenApi.Operation b, OpenApi head, OpenApi.Operation h, List<Change> out) {
        // 参数
        Map<String, Field> bp = params(base, b), hp = params(head, h);
        hp.forEach((name, hf) -> {
            Field bf = bp.get(name);
            if (bf == null) {
                out.add(new Change(hf.required(), k, (hf.required() ? "new required" : "new optional") + " parameter " + name));
            } else if (hf.required() && !bf.required()) {
                out.add(new Change(true, k, "parameter " + name + " became required"));
            } else if (!hf.type().equals(bf.type())) {
                out.add(new Change(true, k, "parameter " + name + " type " + bf.type() + " -> " + hf.type()));
            }
        });
        bp.keySet().stream().filter(n -> !hp.containsKey(n))
                .forEach(n -> out.add(new Change(false, k, "parameter " + n + " removed (ignored by server now)")));

        // 请求体：新增必填 = 破坏（老前端不会传）
        Map<String, Field> bReq = flatten(base, requestSchema(base, b)), hReq = flatten(head, requestSchema(head, h));
        hReq.forEach((f, hf) -> {
            Field bf = bReq.get(f);
            if (bf == null) {
                out.add(new Change(hf.required(), k, "request: new " + (hf.required() ? "required" : "optional") + " field " + f));
            } else if (hf.required() && !bf.required()) {
                out.add(new Change(true, k, "request: field " + f + " became required"));
            } else if (!hf.type().equals(bf.type())) {
                out.add(new Change(true, k, "request: field " + f + " type " + bf.type() + " -> " + hf.type()));
            }
        });
        bReq.keySet().stream().filter(f -> !hReq.containsKey(f))
                .forEach(f -> out.add(new Change(true, k, "request: field " + f + " removed (old clients sending it will get 400)")));

        // 响应：删字段 / 改类型 / 必有变可选 = 破坏（前端在读）
        Map<String, Object> bRes = b.responses(), hRes = h.responses();
        for (String code : new TreeMap<>(bRes).keySet()) {
            if (!code.startsWith("2")) {
                continue;
            }
            if (!hRes.containsKey(code)) {
                out.add(new Change(true, k, "response " + code + " removed"));
                continue;
            }
            Map<String, Field> bf = flatten(base, responseSchema(base, bRes.get(code)));
            Map<String, Field> hf = flatten(head, responseSchema(head, hRes.get(code)));
            bf.forEach((f, old) -> {
                Field now = hf.get(f);
                if (now == null) {
                    out.add(new Change(true, k, "response " + code + ": field " + f + " removed"));
                } else if (!now.type().equals(old.type())) {
                    out.add(new Change(true, k, "response " + code + ": field " + f + " type " + old.type() + " -> " + now.type()));
                } else if (old.required() && !now.required()) {
                    out.add(new Change(true, k, "response " + code + ": field " + f + " no longer guaranteed"));
                }
            });
            hf.keySet().stream().filter(f -> !bf.containsKey(f))
                    .forEach(f -> out.add(new Change(false, k, "response " + code + ": new field " + f)));
        }
        new TreeMap<>(hRes).keySet().stream().filter(c -> !bRes.containsKey(c))
                .forEach(c -> out.add(new Change(false, k, "new response status " + c)));
    }

    private static Map<String, Field> params(OpenApi api, OpenApi.Operation op) {
        Map<String, Field> m = new TreeMap<>();
        for (Object p : OpenApi.list(op.raw().get("parameters"))) {
            Map<String, Object> pm = api.deref(p);
            m.put(pm.get("in") + ":" + pm.get("name"), new Field(type(api, pm.get("schema")), Boolean.TRUE.equals(pm.get("required"))));
        }
        return m;
    }

    private static Object requestSchema(OpenApi api, OpenApi.Operation op) {
        Map<String, Object> rb = api.deref(op.raw().get("requestBody"));
        return OpenApi.map(OpenApi.map(rb.get("content")).get("application/json")).get("schema");
    }

    private static Object responseSchema(OpenApi api, Object response) {
        return OpenApi.map(OpenApi.map(api.deref(response).get("content")).get("application/json")).get("schema");
    }

    private static Map<String, Field> flatten(OpenApi api, Object schema) {
        Map<String, Field> out = new TreeMap<>();
        walk(api, "", schema, out, 0);
        return out;
    }

    private static void walk(OpenApi api, String prefix, Object node, Map<String, Field> out, int depth) {
        if (node == null || depth > 8) {
            return;
        }
        Map<String, Object> s = api.deref(node);
        if ("array".equals(s.get("type"))) {
            walk(api, prefix + "[]", s.get("items"), out, depth + 1);
            return;
        }
        List<Object> required = OpenApi.list(s.get("required"));
        OpenApi.map(s.get("properties")).forEach((name, p) -> {
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            out.put(key, new Field(type(api, p), required.contains(name)));
            walk(api, key, p, out, depth + 1);
        });
    }

    private static String type(OpenApi api, Object schema) {
        if (schema == null) {
            return "any";
        }
        Map<String, Object> s = api.deref(schema);
        String t = String.valueOf(s.getOrDefault("type", s.containsKey("properties") ? "object" : "any"));
        return "array".equals(t) ? "array<" + type(api, s.get("items")) + ">" : t;
    }

    private SpecDiff() {
    }
}
