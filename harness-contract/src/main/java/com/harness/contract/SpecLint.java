package com.harness.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * speclint：把 API 规范"写成代码"而不是写在文档里。Agent 违反任何一条都会被 hook / CI 打回。
 *
 * <pre>java -cp ... com.harness.contract.SpecLint services/user/src/main/resources/openapi.yaml</pre>
 */
public final class SpecLint {

    private static final Pattern PATH = Pattern.compile("^/v[0-9]+(/([a-z0-9-]+|\\{[a-zA-Z]+\\}))+$");

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String a : args) {
            files.add(Path.of(a));
        }
        if (files.isEmpty()) {
            try (Stream<Path> s = Files.list(Path.of("services"))) {
                s.map(d -> d.resolve("src/main/resources/openapi.yaml")).filter(Files::exists).sorted().forEach(files::add);
            }
        }
        List<String> problems = new ArrayList<>();
        for (Path f : files) {
            problems.addAll(lint(f));
        }
        problems.forEach(System.err::println);
        if (!problems.isEmpty()) {
            System.err.println("speclint: " + problems.size() + " problem(s)");
            System.exit(1);
        }
        System.out.println("speclint: " + files.size() + " spec(s) OK");
    }

    public static List<String> lint(Path file) {
        List<String> out = new ArrayList<>();
        OpenApi api;
        try {
            api = OpenApi.load(file);
        } catch (RuntimeException e) {
            return List.of(file + ": load: " + e.getMessage());
        }
        return lint(api, out);
    }

    static List<String> lint(OpenApi api, List<String> out) {
        String file = api.source();
        for (OpenApi.Operation op : api.operations()) {
            String where = file + ": " + op.key() + ": ";
            Map<String, Object> raw = op.raw();
            if (!PATH.matcher(op.path()).matches()) {
                out.add(where + "path must be versioned (/v1/...), lowercase kebab-case, params in {camelCase}");
            }
            if (raw.get("operationId") == null) {
                out.add(where + "missing operationId (前端 SDK 方法名来源)");
            }
            if (raw.get("summary") == null) {
                out.add(where + "missing summary");
            }
            if (OpenApi.list(raw.get("tags")).isEmpty()) {
                out.add(where + "missing tags");
            }
            try {
                Map<String, Object> rb = api.deref(raw.get("requestBody"));
                Map<String, Object> mt = OpenApi.map(OpenApi.map(rb.get("content")).get("application/json"));
                if (mt.containsKey("schema")) {
                    Map<String, Object> schema = api.deref(mt.get("schema"));
                    if ("object".equals(schema.get("type")) && !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                        out.add(where + "request body object must set additionalProperties: false");
                    }
                    if (!mt.containsKey("example") && !mt.containsKey("examples")) {
                        out.add(where + "request body needs an example (供前端/mock 使用)");
                    }
                }
                boolean has2xx = false;
                for (Map.Entry<String, Object> e : op.responses().entrySet()) {
                    String code = e.getKey();
                    Map<String, Object> res = api.deref(e.getValue());
                    Map<String, Object> json = OpenApi.map(OpenApi.map(res.get("content")).get("application/json"));
                    if (code.startsWith("2")) {
                        has2xx = true;
                        if (!json.isEmpty() && !json.containsKey("example") && !json.containsKey("examples")) {
                            out.add(where + code + " response needs an example (Prism mock 依赖它返回真实数据)");
                        }
                    } else if (code.startsWith("4") || code.startsWith("5")) {
                        Object ref = OpenApi.map(json.get("schema")).get("$ref");
                        if (ref == null || !String.valueOf(ref).endsWith("/Error")) {
                            out.add(where + code + " response must use the shared Error schema ($ref: '#/components/responses/Error')");
                        }
                    }
                }
                if (!has2xx) {
                    out.add(where + "no 2xx response declared");
                }
            } catch (IllegalArgumentException e) {
                out.add(where + e.getMessage());
            }
        }
        return out;
    }

    private SpecLint() {
    }
}
