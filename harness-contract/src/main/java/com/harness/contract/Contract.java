package com.harness.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 契约测试扩展。所有 Controller 测试都必须通过它发请求，它机械地保证：
 * <ol>
 *   <li>请求的路由必须在 openapi.yaml 中声明 —— 防止实现出"未文档化"的接口；</li>
 *   <li>每个响应（状态码 + JSON body）都按契约校验 —— 防止实现与契约漂移；</li>
 *   <li>测试类结束时，契约中每个 operation 都至少被调用过一次 —— 防止"写了契约没测"。</li>
 * </ol>
 *
 * <pre>{@code
 * @RegisterExtension
 * static Contract api = Contract.forSpec("src/main/resources/openapi.yaml");
 *
 * api.on(mvc).post("/v1/users", Map.of("name", "Alice")).expect(201).as(User.class);
 * }</pre>
 */
public final class Contract implements AfterAllCallback, TestWatcher {

    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private final OpenApi api;
    private final SchemaValidator validator;
    private final Set<String> exercised = ConcurrentHashMap.newKeySet();
    private volatile boolean anyTestFailed;

    private Contract(OpenApi api) {
        this.api = api;
        this.validator = new SchemaValidator(api);
    }

    public static Contract forSpec(String path) {
        return new Contract(OpenApi.load(Path.of(path)));
    }

    public Client on(MockMvc mvc) {
        return new Client(mvc);
    }

    @Override
    public void testFailed(ExtensionContext ctx, Throwable cause) {
        anyTestFailed = true;
    }

    @Override
    public void afterAll(ExtensionContext ctx) {
        if (anyTestFailed || ctx.getExecutionException().isPresent()) {
            return; // 已有失败，别用覆盖率噪音盖住真正的错误
        }
        Set<String> missing = new TreeSet<>();
        for (OpenApi.Operation op : api.operations()) {
            if (!exercised.contains(op.key())) {
                missing.add(op.operationId() + " (" + op.key() + ")");
            }
        }
        if (!missing.isEmpty()) {
            fail("contract coverage: operations never exercised by tests: " + missing);
        }
    }

    /** 绑定到一个 MockMvc 的请求入口。 */
    public final class Client {
        private final MockMvc mvc;

        private Client(MockMvc mvc) {
            this.mvc = mvc;
        }

        public Response get(String uri) {
            return send(HttpMethod.GET, uri, null);
        }

        public Response post(String uri, Object body) {
            return send(HttpMethod.POST, uri, body);
        }

        public Response put(String uri, Object body) {
            return send(HttpMethod.PUT, uri, body);
        }

        public Response patch(String uri, Object body) {
            return send(HttpMethod.PATCH, uri, body);
        }

        public Response delete(String uri) {
            return send(HttpMethod.DELETE, uri, null);
        }

        /** body 为 String 时原样发送（用于构造非法 JSON），其他对象序列化为 JSON。 */
        public Response send(HttpMethod method, String uri, Object body) {
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            Optional<OpenApi.Operation> op = api.match(method.name(), path);
            if (op.isEmpty()) {
                fail(method + " " + path + " is not declared in the contract (" + api.source() + ")"
                        + (api.pathExists(path) ? ": method not declared for this path" : ""));
            }
            exercised.add(op.get().key());

            MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, uri)
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                try {
                    String raw = body instanceof String s ? s : JSON.writeValueAsString(body);
                    req.contentType(MediaType.APPLICATION_JSON).content(raw.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            }
            MockHttpServletResponse res;
            try {
                res = mvc.perform(req).andReturn().getResponse();
            } catch (Exception e) {
                throw new AssertionError(method + " " + uri + " threw " + e, e);
            }
            verify(op.get(), method + " " + uri, res);
            return new Response(res);
        }
    }

    private void verify(OpenApi.Operation op, String call, MockHttpServletResponse res) {
        String status = String.valueOf(res.getStatus());
        Map<String, Object> responses = op.responses();
        Object declared = responses.containsKey(status) ? responses.get(status) : responses.get("default");
        String body = body(res);
        if (declared == null) {
            fail("response violates contract for " + call + ": status " + status
                    + " is not documented (declared: " + responses.keySet() + ")\nbody=" + body);
        }
        Map<String, Object> content = OpenApi.map(api.deref(declared).get("content"));
        Object schema = OpenApi.map(content.get("application/json")).get("schema");
        if (schema == null) {
            return;
        }
        if (body.isBlank()) {
            fail("response violates contract for " + call + " (status " + status + "): empty body, expected JSON");
        }
        Object value;
        try {
            value = JSON.readValue(body, Object.class);
        } catch (Exception e) {
            throw new AssertionError("response for " + call + " is not JSON: " + body, e);
        }
        List<String> errors = validator.validate(value, schema);
        if (!errors.isEmpty()) {
            fail("response violates contract for " + call + " (status " + status + "):\n  - "
                    + String.join("\n  - ", errors) + "\nbody=" + body);
        }
    }

    private static String body(MockHttpServletResponse res) {
        try {
            return res.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 响应断言的小 DSL。 */
    public static final class Response {
        private final MockHttpServletResponse raw;

        Response(MockHttpServletResponse raw) {
            this.raw = raw;
        }

        public Response expect(int status) {
            assertEquals(status, raw.getStatus(), () -> "unexpected status, body=" + body(raw));
            return this;
        }

        public <T> T as(Class<T> type) {
            try {
                return JSON.readValue(body(raw), type);
            } catch (Exception e) {
                throw new AssertionError("cannot decode body as " + type.getSimpleName() + ": " + body(raw), e);
            }
        }

        public String header(String name) {
            return raw.getHeader(name);
        }

        public MockHttpServletResponse raw() {
            return raw;
        }
    }
}
