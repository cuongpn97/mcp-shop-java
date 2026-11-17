package com.example.shop.domain.customers;

import com.example.shop.domain.BusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    public record Customer(UUID id, String name, String phone, String email, String note, OffsetDateTime createdAt,
                           int orderCount) {
    }

    private final JdbcClient jdbc;

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Name contains the query, or phone contains its digits. */
    public List<Customer> find(UUID shopId, String query, int limit) {
        String digits = query.replaceAll("\\D", "");
        return jdbc.sql("""
                SELECT c.id, c.name, c.phone, c.email, c.note, c.created_at,
                       (SELECT count(*) FROM orders o WHERE o.customer_id = c.id AND o.status <> 'cancelled')::int AS order_count
                  FROM customers c
                 WHERE c.shop_id = :shop
                   AND (lower(c.name) LIKE :like OR (:digits <> '' AND c.phone LIKE :digitsLike))
                 ORDER BY c.name
                 LIMIT :limit""")
                .param("shop", shopId)
                .param("like", "%" + query.toLowerCase() + "%")
                .param("digits", digits)
                .param("digitsLike", "%" + digits + "%")
                .param("limit", limit)
                .query(Customer.class)
                .list();
    }

    public Customer create(UUID shopId, String name, String phone, String email, String note) {
        UUID id = UUID.randomUUID();
        String normalizedPhone = phone == null ? null : normalizePhone(phone);
        try {
            jdbc.sql("""
                    INSERT INTO customers (id, shop_id, name, phone, email, note)
                    VALUES (:id, :shop, :name, :phone, :email, :note)""")
                    .param("id", id).param("shop", shopId).param("name", name.trim())
                    .param("phone", normalizedPhone).param("email", email).param("note", note)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new BusinessException("A customer with phone " + normalizedPhone + " already exists.");
        }
        return new Customer(id, name.trim(), normalizedPhone, email, note, OffsetDateTime.now(), 0);
    }

    /** Vietnamese numbers: +84 / 84 prefix → 0. */
    static String normalizePhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("84") && digits.length() == 11) {
            digits = "0" + digits.substring(2);
        }
        if (digits.length() < 9 || digits.length() > 11) {
            throw new BusinessException("Phone number looks invalid: " + phone);
        }
        return digits;
    }
}
