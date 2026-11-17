package com.example.shop.mcp.tools;

import com.example.shop.domain.BusinessException;
import com.example.shop.domain.orders.OrderService;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.Description;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Module 3: orders — follow up, create, cancel. */
public final class OrderTools {

    private OrderTools() {
    }

    static final String DATE = "\\d{4}-\\d{2}-\\d{2}";

    @Component
    public static class ListOrders implements McpTool<ListOrders.Args> {

        public record Args(
                @Pattern(regexp = "pending|paid|shipped|cancelled") @Description("pending, paid, shipped or cancelled")
                String status,
                @Pattern(regexp = DATE) @Description("From date YYYY-MM-DD (shop timezone), inclusive") String from,
                @Pattern(regexp = DATE) @Description("To date YYYY-MM-DD, inclusive") String to,
                @Description("Only this customer's orders (id from find_customers)") UUID customer_id,
                @Min(1) @Max(50) @Description("Rows to return, default 20") Integer limit,
                @Min(0) @Description("Rows to skip, for paging") Integer offset) {
        }

        private final OrderService orders;

        public ListOrders(OrderService orders) {
            this.orders = orders;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("list_orders", "List orders",
                    "Orders newest first with code, status, total, customer and channel. Filter by status, date "
                            + "range and customer. E.g. 'which orders are still pending today?'",
                    Args.class, Area.ORDERS, "orders_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            return ToolOutput.rows(orders.list(ctx.shopId(), a.status(), date(a.from()), date(a.to()), a.customer_id(),
                    a.limit() == null ? 20 : a.limit(), a.offset() == null ? 0 : a.offset()));
        }
    }

    @Component
    public static class GetOrder implements McpTool<GetOrder.Args> {

        public record Args(@NotBlank @Size(max = 16) @Description("Order code, e.g. DH1024") String code) {
        }

        private final OrderService orders;

        public GetOrder(OrderService orders) {
            this.orders = orders;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("get_order", "Order details",
                    "One order with its lines (SKU, name, quantity, unit price), customer, note and status.",
                    Args.class, Area.ORDERS, "orders_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            return ToolOutput.of(orders.get(ctx.shopId(), a.code())
                    .orElseThrow(() -> new BusinessException("Order " + a.code() + " not found.")));
        }
    }

    @Component
    public static class CreateOrder implements McpTool<CreateOrder.Args> {

        public record Item(@NotBlank @Size(max = 40) @Description("Product SKU") String sku,
                           @NotNull @Min(1) @Max(999) @Description("Quantity") Integer qty) {
        }

        public record Args(
                @Description("Customer id from find_customers; omit for a walk-in sale") UUID customer_id,
                @NotEmpty @Size(max = 30) @Description("Lines to sell") List<@Valid Item> items,
                @Size(max = 500) @Description("Order note, e.g. delivery time") String note) {
        }

        private final OrderService orders;

        public CreateOrder(OrderService orders) {
            this.orders = orders;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.confirmedWrite("create_order", "Create an order",
                    "Creates a pending order at current prices and takes the items out of stock. Two steps: the "
                            + "first call returns the priced preview and a confirm_token; call again with the token "
                            + "after the user agrees.",
                    Args.class, Area.ORDERS, "orders_write", false);
        }

        @Override
        public Object preview(Args a, ToolContext ctx) {
            var q = orders.quote(ctx.shopId(), a.customer_id(), lines(a));
            return Map.of("action", "Create order", "customer", q.customerName() == null ? "walk-in" : q.customerName(),
                    "lines", q.lines().stream().map(l -> Map.of("sku", l.sku(), "name", l.name(), "qty", l.qty(),
                            "unit_price", l.unitPrice(), "amount", l.amount(), "stock_after", l.onHand() - l.qty())).toList(),
                    "total", q.total(), "currency", ctx.shop().currency());
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            var order = orders.create(ctx.shopId(), a.customer_id(), lines(a), CatalogTools.blankToNull(a.note()),
                    ctx.userId(), "ai");
            return ToolOutput.written(order, "order", order.code());
        }

        private static List<OrderService.Line> lines(Args a) {
            return a.items().stream().map(i -> new OrderService.Line(i.sku(), i.qty())).toList();
        }
    }

    @Component
    public static class CancelOrder implements McpTool<CancelOrder.Args> {

        public record Args(
                @NotBlank @Size(max = 16) @Description("Order code, e.g. DH1024") String code,
                @NotBlank @Size(max = 200) @Description("Why it is cancelled, as said by the user") String reason) {
        }

        private final OrderService orders;

        public CancelOrder(OrderService orders) {
            this.orders = orders;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.confirmedWrite("cancel_order", "Cancel an order",
                    "Cancels a pending or paid order and puts its items back in stock. Cannot be undone. Two "
                            + "steps: preview + confirm_token, then the real call after the user agrees.",
                    Args.class, Area.ORDERS, "orders_write", true);
        }

        @Override
        public Object preview(Args a, ToolContext ctx) {
            var o = orders.get(ctx.shopId(), a.code())
                    .orElseThrow(() -> new BusinessException("Order " + a.code() + " not found."));
            if (!o.status().equals("pending") && !o.status().equals("paid")) {
                throw new BusinessException("Order " + o.code() + " is " + o.status() + " and cannot be cancelled.");
            }
            return Map.of("action", "Cancel order", "order", o.code(), "status", o.status(), "total", o.total(),
                    "customer", o.customerName() == null ? "walk-in" : o.customerName(), "items_back_to_stock",
                    o.items(), "reason", a.reason());
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            var order = orders.cancel(ctx.shopId(), a.code(), a.reason(), ctx.userId());
            return ToolOutput.written(order, "order", order.code());
        }
    }

    static LocalDate date(String s) {
        return s == null ? null : LocalDate.parse(s);
    }
}
