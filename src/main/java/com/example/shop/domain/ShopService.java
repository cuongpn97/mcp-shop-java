package com.example.shop.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ShopService {

    public record Shop(UUID id, String name, String currency, String timezone, String status) {
        public LocalDate today() {
            return LocalDate.now(ZoneId.of(timezone));
        }
    }

    private final JdbcClient jdbc;

    public ShopService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Shop> find(UUID shopId) {
        return jdbc.sql("SELECT id, name, currency, timezone, status FROM shops WHERE id = :id")
                .param("id", shopId)
                .query(Shop.class)
                .optional();
    }
}
