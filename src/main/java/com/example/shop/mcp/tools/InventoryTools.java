package com.example.shop.mcp.tools;

import com.example.shop.domain.inventory.InventoryService;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.Description;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

/** Module 4: inventory — what to reorder, record stock received or lost. */
public final class InventoryTools {

    private InventoryTools() {
    }

    @Component
    public static class LowStock implements McpTool<LowStock.Args> {

        public record Args(@Min(1) @Max(50) @Description("Rows to return, default 20") Integer limit) {
        }

        private final InventoryService inventory;

        public LowStock(InventoryService inventory) {
            this.inventory = inventory;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("get_low_stock", "Low stock",
                    "Products at or below their reorder level, the most urgent first, with units on hand.",
                    Args.class, Area.INVENTORY, "inventory_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            return ToolOutput.rows(inventory.lowStock(ctx.shopId(), a.limit() == null ? 20 : a.limit()));
        }
    }

    @Component
    public static class AdjustStock implements McpTool<AdjustStock.Args> {

        public record Args(
                @NotBlank @Size(max = 40) @Description("Product SKU") String sku,
                @NotNull @Min(-10000) @Max(10000)
                @Description("Units to add (received goods, positive) or remove (damaged/lost, negative)") Integer delta,
                @NotBlank @Size(max = 200) @Description("Reason, e.g. 'Received from supplier', 'Damaged'") String reason) {
        }

        private final InventoryService inventory;

        public AdjustStock(InventoryService inventory) {
            this.inventory = inventory;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.confirmedWrite("adjust_stock", "Adjust stock",
                    "Adds or removes units of one product with a reason; stock never goes below zero. Two steps: "
                            + "preview (before/after) + confirm_token, then the real call after the user agrees.",
                    Args.class, Area.INVENTORY, "inventory_write", true);
        }

        @Override
        public Object preview(Args a, ToolContext ctx) {
            return inventory.preview(ctx.shopId(), a.sku(), a.delta(), a.reason());
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            var adj = inventory.adjust(ctx.shopId(), a.sku(), a.delta(), a.reason(), ctx.userId());
            return ToolOutput.written(adj, "stock_adjustment", adj.sku());
        }
    }
}
