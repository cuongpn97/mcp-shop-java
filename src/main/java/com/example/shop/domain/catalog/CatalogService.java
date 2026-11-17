package com.example.shop.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    public record Product(String sku, String name, String category, BigDecimal price, int onHand, boolean active) {
    }

    private static final String SELECT = """
            SELECT p.sku, p.name, p.category, p.price, COALESCE(s.on_hand, 0) AS on_hand, p.active
              FROM products p
              LEFT JOIN stock s ON s.shop_id = p.shop_id AND s.product_id = p.id
             WHERE p.shop_id = :shop""";

    private final JdbcClient jdbc;

    public CatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Name/SKU contains {@code query} (case-insensitive), optionally one category, active only. */
    public List<Product> search(UUID shopId, String query, String category, int limit, int offset) {
        return jdbc.sql(SELECT + """
                    AND p.active
                    AND (CAST(:q AS text) IS NULL OR lower(p.name) LIKE :like OR lower(p.sku) LIKE :like)
                    AND (CAST(:cat AS text) IS NULL OR lower(p.category) = lower(:cat))
                  ORDER BY p.name
                  LIMIT :limit OFFSET :offset""")
                .param("shop", shopId)
                .param("q", query)
                .param("like", query == null ? null : "%" + query.toLowerCase() + "%")
                .param("cat", category)
                .param("limit", limit)
                .param("offset", offset)
                .query(Product.class)
                .list();
    }

    public Optional<Product> bySku(UUID shopId, String sku) {
        return jdbc.sql(SELECT + " AND lower(p.sku) = lower(:sku)")
                .param("shop", shopId)
                .param("sku", sku)
                .query(Product.class)
                .optional();
    }
}
