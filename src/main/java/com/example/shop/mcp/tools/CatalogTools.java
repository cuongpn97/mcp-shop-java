package com.example.shop.mcp.tools;

import com.example.shop.domain.BusinessException;
import com.example.shop.domain.catalog.CatalogService;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.Description;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

/** Module 1: catalog — what we sell, at what price, how many are left. */
public final class CatalogTools {

    private CatalogTools() {
    }

    @Component
    public static class SearchProducts implements McpTool<SearchProducts.Args> {

        public record Args(
                @Size(max = 100) @Description("Words in the product name or SKU, e.g. 'black tee'. Omit to list all.")
                String query,
                @Size(max = 80) @Description("Exact category, e.g. 'T-shirts'") String category,
                @Min(1) @Max(50) @Description("Rows to return, default 20") Integer limit,
                @Min(0) @Description("Rows to skip, for paging") Integer offset) {
        }

        private final CatalogService catalog;

        public SearchProducts(CatalogService catalog) {
            this.catalog = catalog;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("search_products", "Search products",
                    "Active products whose name or SKU contains the query, with price and units on hand. Use it to "
                            + "answer 'do we have…', 'how much is…' and to find SKUs before creating an order.",
                    Args.class, Area.CATALOG, "catalog_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            return ToolOutput.rows(catalog.search(ctx.shopId(), blankToNull(a.query()), blankToNull(a.category()),
                    a.limit() == null ? 20 : a.limit(), a.offset() == null ? 0 : a.offset()));
        }
    }

    @Component
    public static class GetProduct implements McpTool<GetProduct.Args> {

        public record Args(@NotBlank @Size(max = 40) @Description("Product SKU, e.g. TEE-BLK-M") String sku) {
        }

        private final CatalogService catalog;

        public GetProduct(CatalogService catalog) {
            this.catalog = catalog;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("get_product", "Product details",
                    "One product by SKU: name, category, price, units on hand, active or not.",
                    Args.class, Area.CATALOG, "catalog_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            return ToolOutput.of(catalog.bySku(ctx.shopId(), a.sku())
                    .orElseThrow(() -> new BusinessException("No product with SKU " + a.sku() + "; use search_products.")));
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
