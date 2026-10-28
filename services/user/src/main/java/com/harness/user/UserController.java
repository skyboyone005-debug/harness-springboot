package com.harness.user;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.harness.common.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 路由与 openapi.yaml 的 paths 一一对应（契约测试强制）。 */
@RestController
@RequestMapping("/v1/users")
class UserController {

    private final UserStore store;

    UserController(UserStore store) {
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    User create(@Valid @RequestBody CreateUserRequest in) {
        return store.create(in.name().trim(), in.email().toLowerCase(Locale.ROOT));
    }

    @GetMapping
    Map<String, List<User>> list(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return Map.of("items", store.list(limit));
    }

    @GetMapping("/{id}")
    User get(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> ApiException.notFound("user"));
    }
}
