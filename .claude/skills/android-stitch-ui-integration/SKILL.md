---
argument-hint: \<thiết kế/screenshot/code/link từ Stitch, màn hình mục
  tiêu\>
description: Chuyển đổi thiết kế từ Google Stitch (screenshot, MCP,
  share link, DESIGN.md, HTML/CSS, Compose hoặc mô tả) thành Jetpack Compose phù hợp tối đa với
  UI/UX của Stitch và kiến trúc, Design System, component, resource,
  state và coding conventions hiện có của project Android EpubPro. Sử
  dụng khi user cung cấp thiết kế Stitch hoặc yêu cầu triển khai UI
  Stitch vào app. Luôn trả lời bằng tiếng Việt.
language: vi
name: android-stitch-ui-integration
user-invocable: true
---

# Android Stitch UI Integration

Skill chuẩn hóa quy trình **Stitch → Android/Jetpack Compose** cho
project EpubPro.

Mục tiêu:

1.  Giữ **Visual Fidelity** cao nhất có thể so với thiết kế Stitch.
2.  Tận dụng tối đa Architecture, Design System, component và code hiện
    có.
3.  Không phá business logic hoặc kiến trúc hiện tại chỉ để phục vụ UI.
4.  Tích hợp UI theo chuẩn Jetpack Compose và coding conventions của
    project.
5.  Sau khi implement phải **build/run và visual validation**, không coi
    việc compile thành công là hoàn tất.

------------------------------------------------------------------------

# 1. Source of Truth

Phải phân biệt rõ nguồn sự thật cho từng loại thông tin.

Phạm vi                                          Source of Truth
  ------------------------------------------------ -------------------------------------------
Visual/UI/UX                                     Thiết kế Stitch
Layout, spacing, typography, icon, image ratio   Stitch
Architecture                                     Android project hiện tại
Business logic                                   ViewModel / UseCase / Repository hiện tại
Design tokens                                    Design System hiện tại của EpubPro
Existing reusable components                     Component hiện có trong project
Navigation                                       Navigation hiện tại của project

## Quy tắc ưu tiên

### Visual

**Stitch là nguồn chuẩn.**

Không tự ý thay đổi layout chỉ vì cách implement khác với HTML/CSS của
Stitch.

### Architecture

**Source code hiện tại là nguồn chuẩn.**

Không tự ý: - đổi architecture; - migrate XML sang Compose; - đổi
navigation; - đổi ViewModel; - đổi dependency; - tạo Design System
mới; - tạo Theme mới.

Chỉ thay đổi khi cần thiết và phù hợp với cấu trúc hiện tại.

### Business Logic

UI mới phải sử dụng logic hiện có.

Không di chuyển business logic vào Composable chỉ để làm UI hoạt động.

------------------------------------------------------------------------

# 2. Input từ Stitch

Skill có thể nhận một hoặc nhiều loại input:

## 2.0 Xác định nguồn input và khả năng truy cập

Trước khi phân tích visual, xác định input nào thực sự có thể đọc được:

1. Nếu Stitch MCP/SDK tools khả dụng, dùng structured data để lấy screen,
   component, token và asset; không giả định tool tồn tại nếu chưa kiểm tra.
2. Nếu user cung cấp share link, thử mở bằng công cụ truy cập hiện có. Nếu
   link yêu cầu đăng nhập, hết quyền hoặc không tải được, báo rõ và yêu cầu
   screenshot/export thay thế; không suy đoán nội dung bị thiếu.
3. Nếu có `DESIGN.md`, dùng file này để hiểu ý nghĩa token, typography,
   spacing, accessibility và quy tắc thương hiệu; vẫn dùng screenshot để
   kiểm chứng appearance.
4. Nếu chỉ có mô tả bằng văn bản, đánh dấu các giá trị visual là suy luận
   và xác nhận các điểm ảnh hưởng lớn trước khi kết luận visual fidelity.

Không coi source không truy cập được là source of truth đã được kiểm chứng.

## 2.1 Screenshot / Image

Screenshot là **Visual Reference chính**.

Dùng để phân tích:

-   kích thước tương đối;
-   spacing;
-   alignment;
-   typography;
-   màu sắc;
-   shape;
-   icon;
-   image ratio;
-   component hierarchy;
-   trạng thái hiển thị.

Không được chỉ dựa vào screenshot để suy đoán architecture của app.

------------------------------------------------------------------------

## 2.2 Stitch MCP

Nếu có Stitch MCP hoặc structured design data:

-   Ưu tiên sử dụng thông tin có cấu trúc để hiểu component, layout,
    token và asset.
-   Đối chiếu MCP data với screenshot.
-   Screenshot vẫn là nguồn kiểm chứng cuối cùng về appearance.

Không mặc định rằng cấu trúc MCP có thể copy trực tiếp sang Android.

------------------------------------------------------------------------

## 2.3 HTML/CSS/Compose do Stitch sinh ra

Code Stitch là **implementation reference**, không phải source code để
copy-paste.

Có thể sử dụng để hiểu:

-   hierarchy;
-   component structure;
-   spacing;
-   typography;
-   responsive behavior;
-   state;
-   asset usage.

Phải chuyển đổi sang pattern phù hợp với Android project.

## 2.4 DESIGN.md

`DESIGN.md` là design-system reference, không phải Android implementation.

-   Map semantic token của `DESIGN.md` vào Design System hiện có của EpubPro.
-   Kiểm tra các quy tắc accessibility được mô tả trong file.
-   Nếu token trong `DESIGN.md` xung đột với architecture hoặc token hiện có,
    giữ architecture của project và ghi lại mapping/trade-off.

------------------------------------------------------------------------

# 3. Quy trình bắt buộc

## 3.0 Skill phối hợp theo điều kiện

Skill này là workflow Stitch, không thay thế các skill Android chuyên trách.
Luôn phối hợp:

-   `android-compose-router-vi` và `senior-android` cho mọi task Android.
-   `android-compose-styles` khi cần map theme/token/style.
-   `cb-compose-component-design` khi tạo hoặc mở rộng Composable API.
-   `cb-compose-state-and-effects` và `cb-kotlin-concurrency-and-flow` khi
    có state, effect, Flow hoặc navigation event.
-   `android-compose-adaptive` khi có tablet, foldable, wide screen hoặc
    responsive layout.
-   `android-edge-to-edge` khi có system bar, inset hoặc IME.
-   `compose-perf-*` chỉ khi task thực sự liên quan list, jank, stability,
    recomposition hoặc modifier performance.
-   `cb-compose-animations` khi Stitch có motion/transition cần triển khai.
-   `cb-compose-focus-navigation` khi có keyboard, D-pad hoặc TV focus.
-   `android-testing`, `android-testing-setup` và
    `cb-compose-ui-testing-patterns` khi thêm/review UI test hoặc screenshot
    regression test.
-   `android-code-review` khi review implementation hoặc diff.

Không đọc toàn bộ nhóm `compose-perf-*` nếu task không có tín hiệu
performance tương ứng.

Pipeline:

``` text
Stitch Design
     │
     ▼
1. Analyze Visual Design
     │
     ▼
2. Inspect Existing Android Project
     │
     ▼
3. Map Components / Tokens / Assets
     │
     ▼
4. Plan State & Integration
     │
     ▼
5. Implement Compose
     │
     ▼
6. Build / Run
     │
     ▼
7. Capture Screenshot
     │
     ▼
8. Compare With Stitch
     │
     ▼
9. Fix Visual Differences
     │
     ▼
10. Final Self-Review
```

Không bỏ qua bước **Inspect Existing Project** và **Visual Validation**.

------------------------------------------------------------------------

# 4. Bước 1 --- Phân tích thiết kế Stitch

Trước khi code, phải xác định:

## 4.1 Screen structure

Phân tích:

-   Top App Bar;
-   Header;
-   Hero;
-   Section;
-   List;
-   Grid;
-   Card;
-   Button;
-   Chip;
-   Input;
-   Switch;
-   Slider;
-   FAB;
-   Dialog;
-   Bottom Sheet;
-   Dropdown;
-   Navigation;
-   Empty state;
-   Loading state;
-   Error state.

------------------------------------------------------------------------

## 4.2 Component Tree

Ví dụ:

``` text
LibraryScreen
├── LibraryTopBar
├── SearchBar
├── CategoryTabs
├── BookSection
│   ├── BookCard
│   ├── BookCard
│   └── BookCard
└── BottomNavigation
```

Phải xác định component nào:

-   đã tồn tại trong project;
-   có thể reuse;
-   cần tạo mới;
-   chỉ nên là private implementation detail.

Không tạo component mới nếu project đã có component tương đương.

------------------------------------------------------------------------

## 4.3 Visual Specification

Trích xuất tối thiểu:

``` text
Layout
- screen padding
- section spacing
- item spacing
- component width/height
- alignment

Typography
- font family
- font size
- font weight
- line height
- letter spacing

Colors
- background
- surface
- primary
- secondary
- text
- icon
- border
- disabled

Shape
- corner radius
- border width
- clipping

Elevation
- shadow/elevation

Assets
- icons
- illustrations
- images
- avatars
- logos

Behavior
- scrolling
- selected state
- pressed state
- disabled state
- loading
- empty
- error
```

Nếu một giá trị không thể xác định chính xác từ Stitch, sử dụng Design
System hiện có thay vì tự tạo giá trị tùy ý.

------------------------------------------------------------------------

# 5. Bước 2 --- Inspect Android Project

Trước khi implement phải kiểm tra:

1.  Project/module structure.
2.  Target screen hiện tại.
3.  Existing Theme.
4.  Existing Design System.
5.  Existing reusable components.
6.  Existing typography.
7.  Existing color tokens.
8.  Existing shape tokens.
9.  Existing dimensions.
10. Existing string resources.
11. ViewModel.
12. UI State.
13. UseCase.
14. Navigation.
15. Existing asset conventions.
16. Existing Compose/XML convention.
17. Existing dependency versions.

Mục tiêu là **tích hợp vào project**, không tạo một mini-project riêng
bên trong project.

------------------------------------------------------------------------

# 6. Bước 3 --- Mapping Design System

Ưu tiên tuyệt đối Design System hiện có.

Ví dụ:

``` kotlin
MaterialTheme.colorScheme.primary
MaterialTheme.colorScheme.background
MaterialTheme.colorScheme.surface
MaterialTheme.colorScheme.onSurface
MaterialTheme.typography.titleLarge
MaterialTheme.typography.bodyMedium
MaterialTheme.shapes.medium
```

Nếu project có custom tokens thì phải sử dụng custom tokens đó.

## Không hardcode

Không hardcode:

``` kotlin
Color(0xFFD97757)
Color(0xFFFFFFFF)
16.dp
20.dp
```

nếu project đã có token tương ứng.

Chỉ sử dụng literal value khi:

-   Design System chưa có token tương ứng;
-   giá trị thực sự đặc thù của component;
-   và việc tạo token mới là không cần thiết.

Nếu một giá trị xuất hiện nhiều nơi, cân nhắc tạo/reuse token theo
convention hiện tại.

------------------------------------------------------------------------

# 7. Bước 4 --- String Resources

Không hardcode user-facing text trong Composable.

Không:

``` kotlin
Text("Thư viện")
```

Ưu tiên:

``` kotlin
Text(stringResource(R.string.library_title))
```

## Quy tắc

-   Tái sử dụng string đã tồn tại nếu có.
-   Chỉ tạo string mới khi thực sự cần.
-   Với project EpubPro, đặt mọi UI string mới trong
    `core/designsystem/src/main/res/values/strings.xml` theo `AGENTS.md`.
-   Không tự chuyển string sang feature-level resources trừ khi policy
    project được cập nhật rõ ràng.

Ví dụ naming:

``` text
library_title
library_empty_title

profile_title
profile_section_sync

browse_title
online_book_title

action_save
action_cancel
action_retry
```

Chuỗi động dùng placeholder:

``` xml
<string name="book_page">%1$d / %2$d</string>
```

Không nối chuỗi thủ công trong Kotlin.

------------------------------------------------------------------------

# 8. Bước 5 --- Asset Handling

Trước khi thêm asset:

1.  Kiểm tra asset tương ứng đã tồn tại trong project chưa.
2.  Nếu có → reuse.
3.  Nếu chưa có và Stitch cung cấp asset → sử dụng asset đó theo
    convention của project.
4.  Không tự thay bằng icon gần giống nếu asset gốc có sẵn.
5.  Không nhúng Base64 vào source code.
6.  Không dùng remote URL nếu UI yêu cầu local asset.
7.  Tuân thủ convention của project cho PNG, SVG, VectorDrawable và
    image loading.

Nếu project đã có image loader, sử dụng loader hiện tại thay vì thêm thư
viện mới.

------------------------------------------------------------------------

# 9. Bước 6 --- Component & Compose API

Ưu tiên API stateless:

``` kotlin
@Composable
fun BookCard(
    book: BookUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
)
```

Component con không nhận ViewModel trực tiếp.

State nên được hoist:

``` text
Screen
 ├── ViewModel
 ├── UI State
 └── Event
      ↓
Child Composable
```

Ví dụ:

``` kotlin
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onBookClick: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier
)
```

ViewModel chỉ được kết nối ở Screen/container phù hợp.

------------------------------------------------------------------------

# 10. Lifecycle-aware State Collection

Khi Screen thu thập Flow:

``` kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

Không dùng cách collect lifecycle-unsafe nếu project đã sử dụng
lifecycle-aware APIs.

------------------------------------------------------------------------

# 11. LazyColumn / LazyRow / LazyGrid

Ưu tiên stable key khi item có identity:

``` kotlin
items(
    items = uiState.items,
    key = { it.id }
) { item ->
    ...
}
```

Nếu list có nhiều loại item khác nhau và `contentType` mang lại lợi ích
rõ ràng, sử dụng:

``` kotlin
contentType = { it.type }
```

Không bắt buộc thêm `contentType` cho mọi list.

------------------------------------------------------------------------

# 12. Modifier Order

Modifier phải phản ánh đúng behavior và visual design.

Ví dụ:

``` kotlin
Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp)
    .clip(shape)
    .clickable(onClick = onClick)
    .padding(16.dp)
```

Hiểu rõ:

-   padding ngoài → khoảng cách bên ngoài component;
-   clip → giới hạn vùng shape;
-   clickable → vùng tương tác;
-   padding trong → content area.

Không áp dụng một modifier order cố định nếu behavior thực tế yêu cầu
thứ tự khác.

------------------------------------------------------------------------

# 13. State & UI States

Không chỉ implement happy path.

Xác định nếu màn hình có:

``` text
Loading
Success
Empty
Error
Refreshing
Disabled
Selected
Unselected
Authenticated
Unauthenticated
```

Chỉ implement những state phù hợp với business logic thực tế.

Không tự bịa state hoặc business rule chưa tồn tại.

------------------------------------------------------------------------

# 14. Responsive / Insets / Edge-to-edge

UI phải kiểm tra:

-   status bar;
-   navigation bar;
-   gesture navigation;
-   edge-to-edge;
-   IME/keyboard;
-   screen height khác nhau;
-   wide screen;
-   scrolling;
-   content bị che khuất.

Không thêm padding inset trùng với parent đã xử lý inset.

Ví dụ phải xác định rõ screen hay scaffold đang chịu trách nhiệm:

``` kotlin
Scaffold(
    contentWindowInsets = ...
)
```

hoặc child content.

------------------------------------------------------------------------

# 15. Light / Dark Mode

Nếu project hỗ trợ nhiều theme:

-   Không hardcode màu chỉ phù hợp Light.
-   Không hardcode màu chỉ phù hợp Dark.
-   Map Stitch visual vào Design System.
-   Kiểm tra contrast.
-   Không làm thay đổi behavior của theme hiện tại.

Nếu Stitch chỉ cung cấp một theme, sử dụng theme đó làm visual reference
nhưng vẫn phải tuân thủ theme architecture của project.

------------------------------------------------------------------------

# 16. KDoc

Khi tạo function mới, kể cả private Composable đơn giản, bắt buộc thêm
KDoc/Javadoc theo `AGENTS.md`. Comment phải bằng tiếng Việt, mô tả mục đích
và ngữ cảnh; thêm `@param`, `@return`, `@throws` khi phù hợp.

Ví dụ:

``` kotlin
/**
 * Hiển thị thẻ sách với trạng thái đọc hiện tại.
 *
 * @param book Dữ liệu hiển thị của sách.
 * @param onClick Callback khi người dùng chọn sách.
 * @param modifier Modifier tùy biến bố cục bên ngoài.
 */
@Composable
fun BookCard(
    book: BookUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ...
}
```

KDoc viết bằng tiếng Việt theo convention của project.

------------------------------------------------------------------------

# 17. Không phá Architecture

Tuyệt đối tránh:

``` text
Stitch
 ↓
copy HTML/CSS
 ↓
nhúng WebView
```

trừ khi user yêu cầu rõ ràng.

Không:

-   đưa business logic vào UI;
-   tạo ViewModel mới nếu đã có ViewModel phù hợp;
-   duplicate UseCase;
-   duplicate Repository;
-   tạo duplicate Design System;
-   thêm dependency chỉ vì Stitch dùng thư viện khác;
-   migrate XML ↔ Compose ngoài phạm vi yêu cầu.

------------------------------------------------------------------------

# 18. Visual Validation --- Bắt buộc khi môi trường cho phép

Đây là bước quan trọng nhất để đảm bảo UI giống Stitch.

Nếu không có emulator/device, không có quyền đọc Stitch reference hoặc
không thể capture screenshot, ghi `NOT RUN`/`BLOCKED` cùng lý do cụ thể.
Không tự suy diễn kết quả và không báo `PASS` khi chưa thực sự kiểm tra.

Sau khi implement:

``` text
Build
 ↓
Run
 ↓
Capture Android screenshot
 ↓
Compare với Stitch
 ↓
Identify differences
 ↓
Fix
 ↓
Capture lại
```

Không kết luận "done" chỉ vì build thành công.

## Capture contract

Để so sánh có ý nghĩa, Android screenshot và Stitch reference cần cố gắng
đồng nhất:

-   device/viewport và orientation;
-   density, font scale và locale;
-   light/dark theme và system bars;
-   dữ liệu/state hiển thị, permission và authentication state;
-   animation/transition đã ổn định hoặc được tắt khi capture.

Ghi lại các thông số không đồng nhất trước khi đánh giá sai khác visual.

## Visual checklist

### Layout

-   [ ] Screen width/height
-   [ ] Overall hierarchy
-   [ ] Horizontal alignment
-   [ ] Vertical alignment
-   [ ] Screen padding
-   [ ] Section spacing
-   [ ] Item spacing
-   [ ] Component size

### Typography

-   [ ] Font family
-   [ ] Font size
-   [ ] Font weight
-   [ ] Line height
-   [ ] Letter spacing
-   [ ] Text alignment
-   [ ] Text wrapping

### Color

-   [ ] Background
-   [ ] Surface
-   [ ] Primary
-   [ ] Secondary
-   [ ] Text
-   [ ] Icon
-   [ ] Border
-   [ ] Disabled state

### Shape

-   [ ] Corner radius
-   [ ] Border
-   [ ] Clip
-   [ ] Elevation/shadow

### Assets

-   [ ] Correct icon
-   [ ] Correct image
-   [ ] Correct image ratio
-   [ ] Correct asset size
-   [ ] Correct alignment

### Behavior

-   [ ] Scroll
-   [ ] Click
-   [ ] Selected state
-   [ ] Pressed state
-   [ ] Loading
-   [ ] Empty
-   [ ] Error

### System UI

-   [ ] Status bar
-   [ ] Navigation bar
-   [ ] Edge-to-edge
-   [ ] Keyboard/IME
-   [ ] Safe area/insets

### Accessibility

-   [ ] Icon/image có `contentDescription` phù hợp hoặc đánh dấu decorative
       khi đúng ngữ cảnh.
-   [ ] Component tương tác có semantics/role/state để TalkBack sử dụng được.
-   [ ] Touch target đạt kích thước phù hợp với platform và không bị vùng
       clickable che khuất.
-   [ ] Text vẫn đọc được khi font scale tăng và khi bật dark mode.
-   [ ] Focus/traversal hợp lý nếu màn hình hỗ trợ keyboard, D-pad hoặc TV.

------------------------------------------------------------------------

# 19. Visual Difference Priority

Khi screenshot Android khác Stitch, fix theo thứ tự:

``` text
1. Overall layout
2. Component position/size
3. Spacing
4. Typography
5. Colors
6. Shape
7. Icons/assets
8. Shadow/elevation
9. Micro-details
```

Không tối ưu micro-details khi layout tổng thể vẫn sai.

------------------------------------------------------------------------

# 20. Existing Component Reuse

Trước khi tạo component mới:

``` text
Search existing project
       ↓
Found equivalent?
   ├── Yes → Reuse
   └── No  → Create
```

Nếu existing component gần giống nhưng API chưa phù hợp:

-   ưu tiên mở rộng/reuse nếu không ảnh hưởng các caller hiện tại;
-   tránh tạo duplicate component chỉ vì Stitch có tên khác.

------------------------------------------------------------------------

# 21. Performance

Không tối ưu premature.

Chỉ áp dụng optimization khi có lý do:

-   stable key cho list có identity;
-   `contentType` khi hữu ích;
-   tránh tạo object/lambda không cần thiết trong hot path;
-   tránh nested scroll không cần thiết;
-   tránh recomposition do state đặt sai scope;
-   dùng `remember` khi computation thực sự cần memoization.

Không làm code phức tạp chỉ để "tối ưu" một màn hình nhỏ.

------------------------------------------------------------------------

# 22. Self-Review trước khi hoàn tất

## Architecture

-   [ ] Đã inspect project trước khi code.
-   [ ] Không tạo architecture mới.
-   [ ] Không thay đổi business logic ngoài phạm vi.
-   [ ] Không tạo ViewModel/UseCase/Repository duplicate.
-   [ ] Đã reuse component hiện có nếu phù hợp.

## Design System

-   [ ] Đã reuse Theme/token hiện có.
-   [ ] Không hardcode màu đã có token.
-   [ ] Không tạo token duplicate.
-   [ ] Typography dùng hệ thống hiện tại.
-   [ ] Shape/elevation phù hợp.

## Compose

-   [ ] State được hoist hợp lý.
-   [ ] Child Composable không nhận ViewModel trực tiếp.
-   [ ] Flow được collect lifecycle-aware.
-   [ ] Lazy list có stable key khi cần.
-   [ ] Modifier order đúng behavior.
-   [ ] Không tạo recomposition không cần thiết.

## Resources

-   [ ] User-facing text không hardcode.
-   [ ] Đã reuse string hiện có.
-   [ ] String mới đặt đúng module.
-   [ ] Asset được reuse nếu đã tồn tại.
-   [ ] Không thêm dependency không cần thiết.

## UI

-   [ ] Loading/Empty/Error phù hợp với screen.
-   [ ] Light/Dark mode không bị phá.
-   [ ] Insets đúng.
-   [ ] Edge-to-edge đúng.
-   [ ] Keyboard không che content.
-   [ ] `contentDescription`, semantics và touch target phù hợp.
-   [ ] Có kiểm tra font scale/accessibility state khi màn hình yêu cầu.

## Visual

-   [ ] Đã build/run, hoặc ghi `NOT RUN` cùng lý do.
-   [ ] Đã capture screenshot, hoặc ghi `NOT RUN` cùng lý do.
-   [ ] Đã compare với Stitch, hoặc ghi `NOT RUN` cùng lý do.
-   [ ] Đã fix các sai khác lớn.
-   [ ] Không kết luận hoàn tất chỉ dựa trên compile success.

------------------------------------------------------------------------

# 23. Output Format

Khi hoàn thành implementation, trả kết quả bằng tiếng Việt theo format:

Nếu task chỉ là review/design review, dùng `Review Output Format` trong
`AGENTS.md` thay vì cố điền các mục implementation không áp dụng.

## 1. Tổng quan

-   Screen:
-   Module:
-   Approach:
-   Stitch reference:

## 2. Phân tích Stitch

-   Component tree:
-   Visual characteristics:
-   States:
-   Assets:

## 3. Existing Project Integration

-   Existing components reused:
-   Existing Design System reused:
-   Existing ViewModel/State reused:
-   Existing navigation reused:

## 4. Files Changed

Liệt kê chính xác các file đã tạo/sửa:

``` text
path/to/file1.kt
path/to/file2.xml
path/to/file3.kt
```

## 5. Implementation Summary

Mô tả ngắn:

-   UI đã triển khai;
-   state/event;
-   resources;
-   assets;
-   integration.

## 6. Validation

``` text
Build: PASS / FAIL / NOT RUN / BLOCKED
Run: PASS / FAIL / NOT RUN / BLOCKED
Visual comparison: PASS / NEED FIX / NOT RUN / BLOCKED
Reason when NOT RUN/BLOCKED: ...
```

Nếu còn khác biệt visual:

``` text
- ...
- ...
```

Không nói "pixel perfect" nếu chưa thực sự có cơ sở để kết luận.

------------------------------------------------------------------------

# 24. Nguyên tắc cuối cùng

Luôn nhớ:

``` text
STITCH
  │
  │  Visual Source of Truth
  ▼
Visual Analysis
  │
  ▼
Existing Android Project
  │
  ├── Architecture
  ├── Design System
  ├── Components
  ├── ViewModel
  └── Business Logic
  │
  ▼
Jetpack Compose Implementation
  │
  ▼
Build / Run
  │
  ▼
Screenshot
  │
  ▼
Visual Comparison
  │
  ▼
Fix
  │
  ▼
Final Review
```

**Không copy Stitch nguyên bản.**

**Không phá architecture để giống Stitch.**

**Không hy sinh visual fidelity chỉ vì implement dễ hơn.**

Mục tiêu cuối cùng là:

> **Giống thiết kế Stitch về mặt UI/UX trong phạm vi hợp lý, đồng thời
> là một phần native, maintainable và đúng architecture của Android
> project EpubPro.**
