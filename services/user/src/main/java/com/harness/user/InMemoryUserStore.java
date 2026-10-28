package com.harness.user;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import com.harness.common.ApiException;

@Repository
class InMemoryUserStore implements UserStore {

    private final AtomicLong seq = new AtomicLong();
    private final Map<String, User> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByEmail = new ConcurrentHashMap<>();

    @Override
    public User create(String name, String email) {
        String id = "u_" + seq.incrementAndGet();
        if (idByEmail.putIfAbsent(email, id) != null) { // 原子占位，避免并发重复注册
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "email " + email + " already registered");
        }
        User u = new User(id, name, email, Instant.now());
        byId.put(id, u);
        return u;
    }

    @Override
    public Optional<User> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<User> list(int limit) {
        return byId.values().stream()
                .sorted(Comparator.comparing(User::createdAt).thenComparing(User::id))
                .limit(limit)
                .toList();
    }
}
