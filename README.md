# mcp-shop-java

Remote **MCP server** cho một cửa hàng bán lẻ nhỏ. Chủ shop và nhân viên dùng Claude / ChatGPT / Gemini để hỏi số liệu và thao tác (tạo đơn, huỷ đơn, chỉnh tồn kho). Mọi thao tác đều trong giới hạn quyền, có giới hạn tần suất, không ghi trùng và có audit.

Java 21 · Spring Boot 4.1 · MCP Java SDK 2.2 (stateless Streamable HTTP) · Spring Security Resource Server · PostgreSQL · Caffeine · Bucket4j.

```
mvn test                                              # 11 test, gồm 7 test end-to-end trên PostgreSQL embedded
mvn spring-boot:run -Dspring-boot.run.profiles=dev    # cần PostgreSQL ở DB_URL; có sẵn shop demo
```

## Use case: 5 module, 13 tool

| Module | Tool | Ví dụ khi chat |
|---|---|---|
| 0 · Core | `whoami` | AI tự biết shop nào, tiền tệ, ngày hôm nay, được làm gì |
| 1 · Catalog | `search_products`, `get_product` | "Còn áo thun đen size M không, giá bao nhiêu?" |
| 2 · Customers | `find_customers`, `create_customer` ✍️ | "Tìm khách 0901…", "Thêm khách Nguyễn Văn A" |
| 3 · Orders | `list_orders`, `get_order`, `create_order` ✍️🔒, `cancel_order` ✍️🔒⚠️ | "Đơn nào còn pending?", "Tạo đơn 2 quần jean cho chị Lan Anh", "Huỷ DH1024" |
| 4 · Inventory | `get_low_stock`, `adjust_stock` ✍️🔒⚠️ | "Món nào sắp hết?", "Nhập thêm 50 áo trắng M" |
| 5 · Reports | `sales_summary`, `top_products` | "Doanh thu tuần này so với tuần trước?", "Top 5 tháng 10" |

✍️ ghi dữ liệu, cần scope `shop.write` · 🔒 xác nhận 2 bước ở server · ⚠️ destructiveHint

### Ví dụ hội thoại (create_order)

```
User : Tạo đơn 2 quần jean slim 30 cho chị Lan Anh, giao sau 6h tối
AI   → find_customers {query:"Lan Anh"}                       → id d001
AI   → create_order {customer_id, items:[{sku:"JEAN-SLIM-30",qty:2}], note}
       ← preview: 2 × 549.000 = 1.098.000đ, tồn còn 10, confirm_token cf_…   (CHƯA ghi gì)
AI   : "Đơn gồm 2 quần jean slim 30, tổng 1.098.000đ cho chị Lan Anh. Xác nhận tạo?"
User : ok
AI   → create_order {…same args…, confirm_token:"cf_…"}       ← DH1001 pending
```

## Phân quyền: 3 lớp độc lập

| Lớp | Câu hỏi | Lấy từ đâu |
|---|---|---|
| Scope | Kết nối này có được ghi không? | Người dùng tick "cho phép thay đổi" lúc consent → `shop.write` |
| Area | Người dùng có mở mảng này cho AI không? | `mcp_grants.areas` (catalog, orders…) chọn lúc consent |
| Role | Vai trò trong shop có làm được việc này không? | `shop_members` — cùng nguồn với màn hình app |

Cùng một hàm `AccessPolicy.evaluate` vừa **lọc `tools/list`** (tool không được phép sẽ bị ẩn khỏi AI), vừa **chạy lại ở mỗi `tools/call`**.

## Luồng một lần gọi tool (`ToolRunner`)

Các kiểm tra rẻ chạy trước, phần đụng database chạy sau:

```
grant (cache 30s) → bị tạm khoá? → member (cache 30s) → shop (cache 60s) → AccessPolicy
→ rate limit (token bucket trong RAM) → loop guard (RAM) → validate args (Bean Validation)
→ [tool có xác nhận] chưa có token: preview trong read-only TX + phát confirm_token → trả về
                     có token: consume (1 lần, đúng bộ tham số)
→ quota ngày: 1 câu UPSERT … RETURNING
→ [tool ghi] claim idempotency (grant + tool + args) → nếu trùng thì replay kết quả cũ
→ chạy trong transaction với SET LOCAL statement_timeout (read-only cho tool đọc)
→ compact kết quả cho LLM (snake_case, bỏ secret/ID nội bộ, ≤ 50 dòng) + data_notice
→ audit: đẩy vào queue, ghi DB theo batch
```

## Tối ưu (so với cách làm "ngây thơ")

| Vấn đề thường gặp | Cách làm trong repo |
|---|---|
| Dựng lại MCP server và tool list ở mỗi request | Dựng **1 lần** lúc khởi động; schema sinh 1 lần; mỗi request chỉ chạy filter |
| Mỗi lần gọi query grant, quyền, shop | **Caffeine cache** (30–60s). Revoke hoặc tạm khoá xoá cache ngay trên instance hiện tại |
| Rate limit bằng `COUNT(*)` trên bảng audit (chậm, bị trễ khi audit ghi async) | **Token bucket Bucket4j** trong RAM: O(1), chính xác khi bị gọi dồn |
| Trần theo ngày bằng `COUNT(*)` | **Một câu `UPSERT … RETURNING`** trên `mcp_usage_daily`: atomic, không race, dùng chung giữa các instance |
| Audit ghi đồng bộ, 1 insert mỗi lần gọi | **Queue + batch insert** (≤500 dòng/lần, mỗi 500ms, flush khi shutdown) |
| Timeout bằng `Promise.race` / `future.get` nhưng query vẫn chạy ngầm | **`SET LOCAL statement_timeout`**: Postgres huỷ hẳn query; nếu là lệnh ghi thì rollback (đã có test) |
| Tool đọc chạy trong transaction thường | **Read-only TX**, sẵn sàng route sang replica |
| AI tự hỏi trước khi ghi, nhưng chỉ là lời dặn trong prompt | **Xác nhận 2 bước ở server**: preview + `confirm_token` (hash, TTL 5', dùng 1 lần, gắn với args) |
| AI retry làm tạo đơn 2 lần | **Idempotency** theo (grant, tool, args): replay trong 10' |
| Agent bị kẹt vòng lặp | **Loop guard**: gọi y hệt > 8 lần trong 2' thì tạm khoá cả grant 10' (lưu DB) |
| Dữ liệu người dùng nhập chứa prompt injection | `data_notice` trong mọi kết quả + INSTRUCTIONS dặn coi text là dữ liệu + xác nhận 2 bước cho thao tác có hậu quả |
| Output camelCase lẫn ID nội bộ, tốn token | Mapper riêng cho output (snake_case); bỏ `*_id` nội bộ, cắt chuỗi dài, tối đa 50 dòng |

## Thêm một tool

```java
@Component
public class GetSupplier implements McpTool<GetSupplier.Args> {
    public record Args(@NotBlank @Description("Supplier code") String code) {}
    public ToolSpec<Args> spec() {
        return ToolSpec.read("get_supplier", "Supplier", "…when to use it…", Args.class, Area.INVENTORY, "inventory_read");
    }
    public ToolOutput run(Args a, ToolContext ctx) { return ToolOutput.of(suppliers.get(ctx.shopId(), a.code())); }
}
```

Không cần làm gì thêm: schema, phân quyền, giới hạn, timeout, audit đều được áp dụng tự động.

## Kết nối Claude / ChatGPT thật

Repo này là **resource server**. Phần OAuth Authorization Server nên dùng sản phẩm có sẵn (Keycloak, Spring Authorization Server, Auth0) với PKCE S256 và Dynamic Client Registration. Access token cần có các claim:

```
aud = <MCP_PUBLIC_BASE_URL>/mcp     typ = mcp_at     grant_id = <mcp_grants.id>     scope = "shop.read [shop.write]"
```

Đặt `MCP_JWK_SET_URI` để dùng khoá bất đối xứng. Trang consent của bạn ghi `mcp_grants` (shop, areas, scope).
Profile `dev` có `POST /dev/mcp-token/{grantId}` để thử nhanh bằng curl hoặc MCP Inspector. **Không bật profile này ở production.**

```bash
TOKEN=$(curl -s -X POST localhost:8080/dev/mcp-token/00000000-0000-0000-0000-00000000c001 | jq -r .access_token)
curl -s localhost:8080/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sales_summary","arguments":{"from":"2026-11-01","to":"2026-11-17"}}}'
```

## Khi scale nhiều instance

- Token bucket và loop guard đang nằm riêng trên từng instance. Muốn giới hạn chặt toàn cục thì chuyển sang `bucket4j-redis`.
- Cache grant/member có độ trễ tối đa 30s giữa các instance. Muốn thu hồi tức thì thì publish sự kiện (Redis pub/sub) để xoá cache.
- Dọn định kỳ `mcp_confirmations` hết hạn và `mcp_tool_calls` cũ (ví dụ giữ 90 ngày).
