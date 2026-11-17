package com.example.shop.mcp.tools;

import com.example.shop.domain.BusinessException;
import com.example.shop.domain.reports.ReportService;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.Description;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Module 5: reports — how are we doing, what sells. */
public final class ReportTools {

    private ReportTools() {
    }

    static final int MAX_DAYS = 366;

    static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException("`to` must be on or after `from`.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BusinessException("Use a range of at most " + MAX_DAYS + " days.");
        }
    }

    @Component
    public static class SalesSummary implements McpTool<SalesSummary.Args> {

        public record Args(
                @NotNull @Pattern(regexp = OrderTools.DATE) @Description("First day YYYY-MM-DD (shop timezone)") String from,
                @NotNull @Pattern(regexp = OrderTools.DATE) @Description("Last day YYYY-MM-DD, inclusive") String to,
                @Description("Also return the previous period of the same length and the % change. Default true")
                Boolean compare_previous) {
        }

        private final ReportService reports;

        public SalesSummary(ReportService reports) {
            this.reports = reports;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("sales_summary", "Sales summary",
                    "Revenue, number of orders and average order for a date range (cancelled orders excluded), "
                            + "a per-day breakdown and, by default, the previous period with the % change. "
                            + "Max 366 days.",
                    Args.class, Area.REPORTS, "reports_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            LocalDate from = LocalDate.parse(a.from());
            LocalDate to = LocalDate.parse(a.to());
            checkRange(from, to);
            var s = reports.salesSummary(ctx.shopId(), from, to, a.compare_previous() == null || a.compare_previous());
            return new ToolOutput(s, s.byDay().size(), null, null);
        }
    }

    @Component
    public static class TopProducts implements McpTool<TopProducts.Args> {

        public record Args(
                @NotNull @Pattern(regexp = OrderTools.DATE) @Description("First day YYYY-MM-DD") String from,
                @NotNull @Pattern(regexp = OrderTools.DATE) @Description("Last day YYYY-MM-DD, inclusive") String to,
                @Min(1) @Max(50) @Description("How many products, default 10") Integer limit) {
        }

        private final ReportService reports;

        public TopProducts(ReportService reports) {
            this.reports = reports;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("top_products", "Best sellers",
                    "Products ranked by revenue over a date range, with units sold. Max 366 days.",
                    Args.class, Area.REPORTS, "reports_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            LocalDate from = LocalDate.parse(a.from());
            LocalDate to = LocalDate.parse(a.to());
            checkRange(from, to);
            return ToolOutput.rows(reports.topProducts(ctx.shopId(), from, to, a.limit() == null ? 10 : a.limit()));
        }
    }
}
