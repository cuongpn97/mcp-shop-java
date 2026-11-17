package com.example.shop.domain.inventory;

import com.example.shop.domain.BusinessException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryService {

    public record StockLevel(String sku, String name, int onHand, int reorderLevel) {
    }

    public record Adjustment(String sku, String name, int before, int delta, int after, String reason) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public InventoryService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Items at or below their reorder level, the emptiest first. */
    public List<StockLevel> lowStock(UUID shopId, int limit) {
        return jdbc.sql("""
                SELECT p.sku, p.name, s.on_hand, s.reorder_level
                  FROM stock s JOIN products p ON p.id = s.product_id
                 WHERE s.shop_id = :shop AND p.active AND s.on_hand <= s.reorder_level
                 ORDER BY s.on_hand - s.reorder_level, p.name
                 LIMIT :limit""")
                .param("shop", shopId).param("limit", limit)
                .query(StockLevel.class).list();
    }

    /** What an adjustment would do, without writing. */
    public Adjustment preview(UUID shopId, String sku, int delta, String reason) {
        StockLevel s = level(shopId, sku, false);
        check(s, delta);
        return new Adjustment(s.sku(), s.name(), s.onHand(), delta, s.onHand() + delta, reason);
    }

    public Adjustment adjust(UUID shopId, String sku, int delta, String reason, UUID userId) {
        return tx.execute(status -> {
            StockLevel s = level(shopId, sku, true);
            check(s, delta);
            jdbc.sql("""
                    UPDATE stock SET on_hand = on_hand + :d
                     WHERE shop_id = :shop AND product_id = (SELECT id FROM products WHERE shop_id = :shop AND sku = :sku)""")
                    .param("d", delta).param("shop", shopId).param("sku", s.sku()).update();
            jdbc.sql("""
                    INSERT INTO stock_movements (id, shop_id, product_id, delta, reason, created_by)
                    SELECT :id, :shop, id, :d, :reason, :user FROM products WHERE shop_id = :shop AND sku = :sku""")
                    .param("id", UUID.randomUUID()).param("shop", shopId).param("d", delta)
                    .param("reason", reason).param("user", userId).param("sku", s.sku()).update();
            return new Adjustment(s.sku(), s.name(), s.onHand(), delta, s.onHand() + delta, reason);
        });
    }

    private StockLevel level(UUID shopId, String sku, boolean lock) {
        return jdbc.sql("""
                SELECT p.sku, p.name, s.on_hand, s.reorder_level
                  FROM stock s JOIN products p ON p.id = s.product_id
                 WHERE s.shop_id = :shop AND lower(p.sku) = lower(:sku)""" + (lock ? " FOR UPDATE OF s" : ""))
                .param("shop", shopId).param("sku", sku)
                .query(StockLevel.class).optional()
                .orElseThrow(() -> new BusinessException("Unknown SKU " + sku + "; use search_products."));
    }

    private static void check(StockLevel s, int delta) {
        if (delta == 0) {
            throw new BusinessException("delta must not be 0.");
        }
        if (s.onHand() + delta < 0) {
            throw new BusinessException("Stock of " + s.sku() + " would go below zero (" + s.onHand() + " on hand).");
        }
    }
}
