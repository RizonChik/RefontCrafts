# RefontCrafts — Tài liệu

**RefontCrafts** là plugin Minecraft server có giao diện GUI để tạo công thức chế tạo tùy chỉnh trong bàn chế tạo và đe.

Plugin phù hợp cho server survival, RPG, roleplay, economy và custom server cần công thức linh hoạt, kiểm tra quyền và hỗ trợ vật phẩm tùy chỉnh.

## Tính năng

- Trình chỉnh sửa GUI cho công thức bàn chế tạo.
- Trình chỉnh sửa GUI cho công thức đe.
- Hỗ trợ công thức 3x3 theo đúng vị trí và công thức không cần hình dạng cố định.
- Người chơi không cần OP vẫn có thể xem công thức đã lưu.
- Hỗ trợ vật phẩm tùy chỉnh, potion, CustomModelData, tên, lore, enchantment và dữ liệu PDC.
- So sánh vật phẩm linh hoạt hơn với `exact_meta_match: false`.
- Thứ tự vật phẩm trong đe có thể được kiểm tra nghiêm ngặt.
- Tự động cập nhật preview kết quả khi dùng chuột trái, chuột phải và kéo thả item.
- Preview và kết quả trong bàn chế tạo có thể hiển thị stack tới `126`.
- SQLite mặc định, có thể dùng MySQL.

## Yêu cầu

| Mục | Giá trị |
| --- | --- |
| Server software | Bukkit / Spigot / Paper |
| Java | Java 8+ cho server cũ; dùng đúng Java cho phiên bản Minecraft mới hơn |
| Database | SQLite hoặc MySQL |
| Lệnh chính | `/rcrafts` |
| Alias | `/rc`, `/refontcrafts` |

## Cài đặt

1. Tải file `.jar` mới nhất từ Releases hoặc Modrinth.
2. Đặt file vào thư mục `plugins/`.
3. Khởi động lại server.
4. Mở menu chính:

```text
/rcrafts
```

Plugin sẽ tự tạo cấu hình và database trong lần chạy đầu tiên.

## Lệnh

| Lệnh | Mô tả |
| --- | --- |
| `/rcrafts` | Menu chính |
| `/rcrafts view workbench [page]` | Xem công thức bàn chế tạo |
| `/rcrafts view anvil [page]` | Xem công thức đe |
| `/rcrafts recipe` | Tạo công thức bàn chế tạo |
| `/rcrafts anvil` | Tạo công thức đe |
| `/rcrafts reload` | Tải lại cấu hình và công thức |

## Quyền

| Quyền | Mặc định | Mô tả |
| --- | --- | --- |
| `refontcrafts.use` | `true` | Quyền cơ bản để dùng `/rcrafts` |
| `refontcrafts.view` | `true` | Xem công thức |
| `refontcrafts.create.workbench` | `op` | Tạo công thức bàn chế tạo |
| `refontcrafts.create.anvil` | `op` | Tạo công thức đe |
| `refontcrafts.edit.workbench` | `op` | Sửa công thức bàn chế tạo |
| `refontcrafts.edit.anvil` | `op` | Sửa công thức đe |
| `refontcrafts.delete.workbench` | `op` | Xóa công thức bàn chế tạo |
| `refontcrafts.delete.anvil` | `op` | Xóa công thức đe |
| `refontcrafts.reload` | `op` | Tải lại plugin |
| `refontcrafts.notify` | `op` | Thông báo nội bộ |
| `refontcrafts.admin` | `op` | Toàn quyền |

## Cấu hình quan trọng

```yaml
settings:
  exact_meta_match: false
  workbench_strict_shape: true
  workbench_preview_limit: 126

  anvil:
    strict_order: true
```

### `exact_meta_match`

- `true` — so sánh vật phẩm nghiêm ngặt.
- `false` — so sánh dữ liệu quan trọng về hình ảnh và logic, bỏ qua NBT kỹ thuật riêng biệt.

Nên dùng `false` nếu server có shop, Brewery, ExecutableItems hoặc plugin vật phẩm tùy chỉnh khác.

### `workbench_strict_shape`

Nếu bật, công thức bàn chế tạo chỉ hoạt động khi item được đặt đúng vị trí 3x3.

### `workbench_preview_limit`

Giới hạn số lượng hiển thị của kết quả trong bàn chế tạo. `126` là giới hạn ổn định cho preview overstack.

### `anvil.strict_order`

Nếu bật, slot trái và slot phải của đe được xem là khác nhau. Công thức `A + B` sẽ không hoạt động như `B + A`.

## FAQ

### Vì sao preview kết quả giới hạn ở 126?

Đây là giới hạn thực tế để client hiển thị overstack ổn định. Giá trị cao hơn có thể làm slot kết quả hiển thị sai.

### Có thể cho người chơi chỉ xem công thức không?

Có. Chỉ cần cấp:

```text
refontcrafts.use
refontcrafts.view
```

### Báo lỗi ở đâu?

Dùng GitHub Issues: https://github.com/RizonChik/RefontCrafts/issues

## Build từ source

```bash
mvn clean package
```

File `.jar` sẽ được tạo trong thư mục `target/`.
