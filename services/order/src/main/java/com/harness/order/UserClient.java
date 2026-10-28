package com.harness.order;

/** order-service 对 user-service 的依赖边界：只依赖对方契约，不 import 对方代码；测试中用 Mock 替换。 */
public interface UserClient {

    boolean exists(String userId);
}
