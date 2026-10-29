-- 已提交的迁移不可修改（harness 守卫会拦截）；变更请新增 V<n+1>__*.sql
CREATE TABLE orders (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(32)  NOT NULL,
    total_cents BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);

CREATE TABLE order_items (
    order_id    BIGINT      NOT NULL,
    line_no     INT         NOT NULL,
    sku         VARCHAR(64) NOT NULL,
    qty         INT         NOT NULL,
    price_cents INT         NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
