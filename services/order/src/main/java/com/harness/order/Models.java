package com.harness.order;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** 与 openapi.yaml components.schemas 一一对应。 */
final class Models {

    record LineItem(@NotBlank String sku, @NotNull @Min(1) Integer qty, @NotNull @Min(0) Integer priceCents) {
    }

    record CreateOrderRequest(@NotBlank String userId, @NotEmpty List<@Valid LineItem> items) {
    }

    enum Status { pending, paid, cancelled }

    record Order(String id, String userId, List<LineItem> items, long totalCents, Status status, Instant createdAt) {
    }

    private Models() {
    }
}
