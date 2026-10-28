package com.harness.user;

import java.util.List;
import java.util.Optional;

/** 持久化边界。换数据库时只实现这个接口，Controller 与契约测试不变。 */
public interface UserStore {

    User create(String name, String email);

    Optional<User> find(String id);

    List<User> list(int limit);
}
