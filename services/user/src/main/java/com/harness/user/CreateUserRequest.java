package com.harness.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 对应 openapi.yaml components.schemas.CreateUserRequest；校验注解与契约约束保持一致。 */
public record CreateUserRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Email String email) {
}
