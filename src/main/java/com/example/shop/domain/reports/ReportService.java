package com.example.shop.domain.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Sales figures by business day in the shop's timezone. Cancelled orders are excluded. */
@Service
public class ReportService {

    public record Totals(int orders, BigDecimal revenue, BigDecimal averageOrder) {
    }

    public record Day(LocalDate day, int orders, BigDecimal revenue) {
    }

    public record Summary(LocalDate from, LocalDate to, Totals totals, List<Day> byDay, LocalDate previousFrom,
                          LocalDate previousTo, Totals previous, BigDecimal revenueChangePercent) {
    }

    public record TopProduct(String sku, String name, int qty, BigDecimal revenue) {
    }

    private static final String RANGE = """
              FROM orders o JOIN shops s ON s.id = o.shop_id
             WHERE o.shop_id = :shop AND o.status <> 'cancelled'
               AND o.created_at >= CAST(:from AS timestamp) AT TIME ZONE s.timezone
               AND o.created_at < (CAST(:to AS timestamp) + interval '1 day') AT TIME ZONE s.timezone
            """;

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Summary salesSummary(UUID shopId, LocalDate from, LocalDate to, boolean comparePrevious) {
        Totals totals = totals(shopId, from, to);
        List<Day> days = jdbc.sql("""
                SELECT CAST(o.created_at AT TIME ZONE s.timezone AS date) AS day,
                       count(*)::int AS orders, COALESCE(sum(o.total), 0) AS revenue
                """ + RANGE + """
                 GROUP BY 1 ORDER BY 1""")
                .param("shop", shopId).param("from", from).param("to", to)
                .query(Day.class).list();
        if (!comparePrevious) {
            return new Summary(from, to, totals, days, null, null, null, null);
        }
        long length = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(length - 1);
        Totals previous = totals(shopId, prevFrom, prevTo);
        BigDecimal change = previous.revenue().signum() == 0 ? null
                : totals.revenue().subtract(previous.revenue()).multiply(BigDecimal.valueOf(100))
                        .divide(previous.revenue(), 1, RoundingMode.HALF_UP);
        return new Summary(from, to, totals, days, prevFrom, prevTo, previous, change);
    }

    public List<TopProduct> topProducts(UUID shopId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                SELECT p.sku, p.name, sum(i.qty)::int AS qty, sum(i.qty * i.unit_price) AS revenue
                """ + RANGE.replace(
                "FROM orders o JOIN shops s ON s.id = o.shop_id",
                "FROM orders o JOIN shops s ON s.id = o.shop_id JOIN order_items i ON i.order_id = o.id "
                        + "JOIN products p ON p.id = i.product_id") + """
                 GROUP BY p.sku, p.name
                 ORDER BY revenue DESC
                 LIMIT :limit""")
                .param("shop", shopId).param("from", from).param("to", to).param("limit", limit)
                .query(TopProduct.class).list();
    }

    private Totals totals(UUID shopId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT count(*)::int AS orders, COALESCE(sum(o.total), 0) AS revenue, "
                        + "COALESCE(round(avg(o.total), 0), 0) AS average_order " + RANGE)
                .param("shop", shopId).param("from", from).param("to", to)
                .query(Totals.class).single();
    }
}
