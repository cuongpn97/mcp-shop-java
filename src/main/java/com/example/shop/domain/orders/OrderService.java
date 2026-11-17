package com.example.shop.domain.orders;

import com.example.shop.domain.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    public record Line(String sku, int qty) {
    }

    public record QuotedLine(UUID productId, String sku, String name, int qty, BigDecimal unitPrice, BigDecimal amount,
                             int onHand) {
    }

    public record Quote(UUID customerId, String customerName, List<QuotedLine> lines, BigDecimal total) {
    }

    public record OrderSummary(String code, String status, BigDecimal total, String customerName,
                               OffsetDateTime createdAt, String createdVia) {
    }

    public record OrderItem(String sku, String name, int qty, BigDecimal unitPrice) {
    }

    public record OrderDetail(String code, String status, BigDecimal total, String customerName, String customerPhone,
                              String note, String cancelReason, OffsetDateTime createdAt, List<OrderItem> items) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public OrderService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<OrderSummary> list(UUID shopId, String status, LocalDate from, LocalDate to, UUID customerId,
                                   int limit, int offset) {
        return jdbc.sql("""
                SELECT o.code, o.status, o.total, c.name AS customer_name, o.created_at, o.created_via
                  FROM orders o
                  JOIN shops s ON s.id = o.shop_id
                  LEFT JOIN customers c ON c.id = o.customer_id
                 WHERE o.shop_id = :shop
                   AND (CAST(:status AS text) IS NULL OR o.status = :status)
                   AND (CAST(:customer AS uuid) IS NULL OR o.customer_id = :customer)
                   AND (CAST(:from AS date) IS NULL OR o.created_at >= CAST(:from AS timestamp) AT TIME ZONE s.timezone)
                   AND (CAST(:to AS date) IS NULL OR o.created_at < (CAST(:to AS timestamp) + interval '1 day') AT TIME ZONE s.timezone)
                 ORDER BY o.created_at DESC
                 LIMIT :limit OFFSET :offset""")
                .param("shop", shopId).param("status", status).param("customer", customerId)
                .param("from", from).param("to", to).param("limit", limit).param("offset", offset)
                .query(OrderSummary.class)
                .list();
    }

    public Optional<OrderDetail> get(UUID shopId, String code) {
        record Head(UUID id, String code, String status, BigDecimal total, String customerName, String customerPhone,
                    String note, String cancelReason, OffsetDateTime createdAt) {
        }
        return jdbc.sql("""
                SELECT o.id, o.code, o.status, o.total, c.name AS customer_name, c.phone AS customer_phone,
                       o.note, o.cancel_reason, o.created_at
                  FROM orders o LEFT JOIN customers c ON c.id = o.customer_id
                 WHERE o.shop_id = :shop AND o.code = upper(:code)""")
                .param("shop", shopId).param("code", code)
                .query(Head.class)
                .optional()
                .map(h -> new OrderDetail(h.code(), h.status(), h.total(), h.customerName(), h.customerPhone(),
                        h.note(), h.cancelReason(), h.createdAt(),
                        jdbc.sql("""
                                SELECT p.sku, p.name, i.qty, i.unit_price
                                  FROM order_items i JOIN products p ON p.id = i.product_id
                                 WHERE i.order_id = :id ORDER BY p.name""")
                                .param("id", h.id()).query(OrderItem.class).list()));
    }

    /** Prices and checks an order without writing anything (used for previews). */
    public Quote quote(UUID shopId, UUID customerId, List<Line> lines) {
        return price(shopId, customerId, lines, false);
    }

    /** Creates a pending order and takes the stock, all or nothing. */
    public OrderDetail create(UUID shopId, UUID customerId, List<Line> lines, String note, UUID userId, String via) {
        return tx.execute(status -> {
            Quote q = price(shopId, customerId, lines, true);
            UUID orderId = UUID.randomUUID();
            String code = "DH" + jdbc.sql("SELECT nextval('order_code_seq')").query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO orders (id, shop_id, code, customer_id, status, total, note, created_by, created_via)
                    VALUES (:id, :shop, :code, :customer, 'pending', :total, :note, :user, :via)""")
                    .param("id", orderId).param("shop", shopId).param("code", code).param("customer", customerId)
                    .param("total", q.total()).param("note", note).param("user", userId).param("via", via)
                    .update();
            for (QuotedLine l : q.lines()) {
                jdbc.sql("INSERT INTO order_items (order_id, product_id, qty, unit_price) VALUES (:o, :p, :q, :u)")
                        .param("o", orderId).param("p", l.productId()).param("q", l.qty()).param("u", l.unitPrice())
                        .update();
                moveStock(shopId, l.productId(), -l.qty(), "Order " + code, code, userId);
            }
            return get(shopId, code).orElseThrow();
        });
    }

    /** Cancels a pending/paid order and puts its items back in stock. */
    public OrderDetail cancel(UUID shopId, String code, String reason, UUID userId) {
        return tx.execute(status -> {
            record Locked(UUID id, String code, String status) {
            }
            Locked o = jdbc.sql("SELECT id, code, status FROM orders WHERE shop_id = :shop AND code = upper(:code) FOR UPDATE")
                    .param("shop", shopId).param("code", code).query(Locked.class).optional()
                    .orElseThrow(() -> new BusinessException("Order " + code + " not found."));
            if (!o.status().equals("pending") && !o.status().equals("paid")) {
                throw new BusinessException("Order " + o.code() + " is " + o.status() + " and cannot be cancelled.");
            }
            record Item(UUID productId, int qty) {
            }
            for (Item i : jdbc.sql("SELECT product_id, qty FROM order_items WHERE order_id = :id ORDER BY product_id")
                    .param("id", o.id()).query(Item.class).list()) {
                moveStock(shopId, i.productId(), i.qty(), "Cancel " + o.code(), o.code(), userId);
            }
            jdbc.sql("UPDATE orders SET status = 'cancelled', cancel_reason = :reason WHERE id = :id")
                    .param("reason", reason).param("id", o.id()).update();
            return get(shopId, o.code()).orElseThrow();
        });
    }

    private Quote price(UUID shopId, UUID customerId, List<Line> lines, boolean lockStock) {
        if (lines.isEmpty()) {
            throw new BusinessException("An order needs at least one item.");
        }
        String customerName = null;
        if (customerId != null) {
            customerName = jdbc.sql("SELECT name FROM customers WHERE id = :id AND shop_id = :shop")
                    .param("id", customerId).param("shop", shopId).query(String.class).optional()
                    .orElseThrow(() -> new BusinessException("Unknown customer; use find_customers."));
        }
        Map<String, Integer> qtyBySku = new LinkedHashMap<>();
        for (Line l : lines) {
            qtyBySku.merge(l.sku().toUpperCase(), l.qty(), Integer::sum);
        }
        record Row(UUID id, String sku, String name, BigDecimal price, int onHand) {
        }
        // Lock in a fixed order (product id) so two concurrent orders never deadlock.
        List<Row> rows = jdbc.sql("""
                SELECT p.id, p.sku, p.name, p.price, s.on_hand
                  FROM products p JOIN stock s ON s.shop_id = p.shop_id AND s.product_id = p.id
                 WHERE p.shop_id = :shop AND p.active AND upper(p.sku) IN (:skus)
                 ORDER BY p.id""" + (lockStock ? " FOR UPDATE OF s" : ""))
                .param("shop", shopId).param("skus", qtyBySku.keySet())
                .query(Row.class).list();
        if (rows.size() != qtyBySku.size()) {
            List<String> found = rows.stream().map(r -> r.sku().toUpperCase()).toList();
            throw new BusinessException("Unknown or inactive SKU: " + qtyBySku.keySet().stream()
                    .filter(s -> !found.contains(s)).toList() + ". Use search_products.");
        }
        List<QuotedLine> quoted = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Row r : rows) {
            int qty = qtyBySku.get(r.sku().toUpperCase());
            if (qty > r.onHand()) {
                throw new BusinessException("Not enough stock for " + r.sku() + ": " + r.onHand() + " left, " + qty + " asked.");
            }
            BigDecimal amount = r.price().multiply(BigDecimal.valueOf(qty));
            total = total.add(amount);
            quoted.add(new QuotedLine(r.id(), r.sku(), r.name(), qty, r.price(), amount, r.onHand()));
        }
        quoted.sort(Comparator.comparing(QuotedLine::sku));
        return new Quote(customerId, customerName, quoted, total);
    }

    private void moveStock(UUID shopId, UUID productId, int delta, String reason, String ref, UUID userId) {
        jdbc.sql("UPDATE stock SET on_hand = on_hand + :d WHERE shop_id = :shop AND product_id = :p")
                .param("d", delta).param("shop", shopId).param("p", productId).update();
        jdbc.sql("""
                INSERT INTO stock_movements (id, shop_id, product_id, delta, reason, ref_code, created_by)
                VALUES (:id, :shop, :p, :d, :reason, :ref, :user)""")
                .param("id", UUID.randomUUID()).param("shop", shopId).param("p", productId).param("d", delta)
                .param("reason", reason).param("ref", ref).param("user", userId)
                .update();
    }
}
