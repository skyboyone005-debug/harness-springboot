package com.harness.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpecToolsTest {

    static final String BASE = """
            openapi: 3.0.3
            info: { title: t, version: 1.0.0 }
            paths:
              /v1/things:
                post:
                  operationId: createThing
                  summary: create
                  tags: [things]
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          additionalProperties: false
                          required: [name]
                          properties: { name: { type: string } }
                        example: { name: x }
                  responses:
                    '201':
                      description: ok
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/Thing' }
                          example: { id: t_1, name: x }
            components:
              schemas:
                Thing:
                  type: object
                  required: [id, name]
                  properties: { id: { type: string }, name: { type: string } }
            """;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            add optional request field | properties: { name: { type: string } } | properties: { name: { type: string }, color: { type: string } } | false | new optional field color
            remove response field      | properties: { id: { type: string }, name: { type: string } } | properties: { id: { type: string } } | true | field name removed
            change response type       | id: { type: string }, name            | id: { type: integer }, name                                    | true  | type string -> integer
            remove operation           | '    post:'                            | '    put:'                                                     | true  | operation removed
            """)
    void diff(String name, String from, String to, boolean breaking, String want) {
        String head = BASE.replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to));
        assertTrue(!head.equals(BASE), "fixture replacement did not apply");
        List<SpecDiff.Change> changes = SpecDiff.diff(OpenApi.parse(BASE), OpenApi.parse(head));
        assertTrue(changes.stream().anyMatch(c -> c.msg().contains(want) && c.breaking() == breaking),
                () -> "want '" + want + "' breaking=" + breaking + ", got " + changes);
    }

    @Test
    void newRequiredRequestFieldIsBreaking() {
        String head = BASE.replaceFirst("required: \\[name]", "required: [name, size]")
                .replaceFirst("properties: \\{ name: \\{ type: string } }", "properties: { name: { type: string }, size: { type: integer } }");
        assertTrue(SpecDiff.diff(OpenApi.parse(BASE), OpenApi.parse(head)).stream()
                .anyMatch(c -> c.breaking() && c.msg().equals("request: new required field size")));
    }

    @Test
    void identicalSpecsHaveNoChanges() {
        assertEquals(List.of(), SpecDiff.diff(OpenApi.parse(BASE), OpenApi.parse(BASE)));
    }

    @Test
    void lintAcceptsConventionalSpecAndRejectsViolations() {
        assertEquals(List.of(), SpecLint.lint(OpenApi.parse(BASE), new ArrayList<>()));
        String bad = BASE.replace("      operationId: createThing\n", "").replace("additionalProperties: false\n", "\n");
        List<String> problems = SpecLint.lint(OpenApi.parse(bad), new ArrayList<>());
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing operationId")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("additionalProperties")), problems::toString);
    }

    @Test
    void schemaValidatorReportsDrift() {
        OpenApi api = OpenApi.parse(BASE);
        Object thing = Map.of("$ref", "#/components/schemas/Thing");
        SchemaValidator v = new SchemaValidator(api);
        assertEquals(List.of(), v.validate(Map.of("id", "t_1", "name", "x"), thing));
        assertEquals(List.of("$: required property 'name' is missing"), v.validate(Map.of("id", "t_1", "nm", "x"), thing));
        assertEquals(List.of("$.id: expected string, got number"), v.validate(Map.of("id", 1, "name", "x"), thing));
    }

    @Test
    void routeMatchingPrefersLiteralSegments() {
        String spec = BASE.replace("paths:\n", """
                paths:
                  /v1/things/{id}:
                    get: { operationId: getThing, responses: { '200': { description: ok } } }
                  /v1/things/stats:
                    get: { operationId: thingStats, responses: { '200': { description: ok } } }
                """);
        OpenApi api = OpenApi.parse(spec);
        assertEquals("thingStats", api.match("GET", "/v1/things/stats").orElseThrow().operationId());
        assertEquals("getThing", api.match("GET", "/v1/things/t_1").orElseThrow().operationId());
        assertTrue(api.match("DELETE", "/v1/things/t_1").isEmpty());
        assertTrue(api.pathExists("/v1/things/t_1"));
    }
}
