package com.example.shop.mcp.tools;

import com.example.shop.domain.customers.CustomerService;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.Description;
import com.example.shop.mcp.core.McpTool;
import com.example.shop.mcp.core.ToolContext;
import com.example.shop.mcp.core.ToolOutput;
import com.example.shop.mcp.core.ToolSpec;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

/** Module 2: customers — find a buyer, add a new one. */
public final class CustomerTools {

    private CustomerTools() {
    }

    @Component
    public static class FindCustomers implements McpTool<FindCustomers.Args> {

        public record Args(
                @NotBlank @Size(min = 2, max = 100) @Description("Part of the name or of the phone number") String query,
                @Min(1) @Max(50) @Description("Rows to return, default 10") Integer limit) {
        }

        private final CustomerService customers;

        public FindCustomers(CustomerService customers) {
            this.customers = customers;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.read("find_customers", "Find customers",
                    "Customers whose name contains the query or whose phone contains its digits, with their id "
                            + "(needed by create_order) and number of orders.",
                    Args.class, Area.CUSTOMERS, "customers_read");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            return ToolOutput.rows(customers.find(ctx.shopId(), a.query().trim(), a.limit() == null ? 10 : a.limit()));
        }
    }

    @Component
    public static class CreateCustomer implements McpTool<CreateCustomer.Args> {

        public record Args(
                @NotBlank @Size(max = 200) @Description("Full name") String name,
                @Size(max = 20) @Description("Phone number, e.g. 0901234567") String phone,
                @Email @Size(max = 200) @Description("Email address") String email,
                @Size(max = 500) @Description("Free note, e.g. delivery preference") String note) {
        }

        private final CustomerService customers;

        public CreateCustomer(CustomerService customers) {
            this.customers = customers;
        }

        @Override
        public ToolSpec<Args> spec() {
            return ToolSpec.write("create_customer", "Add a customer",
                    "Adds a customer. Check with find_customers first to avoid duplicates; a phone number can "
                            + "belong to only one customer.",
                    Args.class, Area.CUSTOMERS, "customers_write");
        }

        @Override
        public ToolOutput run(Args a, ToolContext ctx) {
            var c = customers.create(ctx.shopId(), a.name(), CatalogTools.blankToNull(a.phone()),
                    CatalogTools.blankToNull(a.email()), CatalogTools.blankToNull(a.note()));
            return ToolOutput.written(c, "customer", c.phone() == null ? c.name() : c.phone());
        }
    }
}
