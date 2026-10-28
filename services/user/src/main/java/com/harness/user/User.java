package com.harness.user;

import java.time.Instant;

/** 对应 openapi.yaml components.schemas.User。字段名即契约，改名会被契约测试拦下。 */
public record User(String id, String name, String email, Instant createdAt) {
}
