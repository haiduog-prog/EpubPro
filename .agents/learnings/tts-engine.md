# Android Text-to-Speech & Background Media Architecture

> Tổng hợp kiến thức về hệ thống TTS Engine, Sherpa-ONNX Offline AI Voice, Foreground Service, MediaSessionCompat và đồng bộ Highlight trong WebView EPUB Reader.
> Cập nhật lần cuối: 2026-10-29

---

## Architecture

### IndexedDB Database Isolation cho Voice Storage
- **Ngày**: 2026-10-29
- **Chi tiết**: Tách `TtsVoiceStorage` sang cơ sở dữ liệu IndexedDB riêng `EpubPro_Voice_DB` thay vì gộp chung vào `EpubPro_Web_DB`. Việc hai module độc lập cùng mở một database với các version khác nhau (v1 vs v2) sẽ gây ra tình trạng `blocked` vĩnh viễn trên trình duyệt khi kết nối cũ chưa đóng, làm đơ toàn bộ luồng khởi tạo bất đồng bộ.
- **Files liên quan**: `web-reader/js/voice-catalog.js`, `web-reader/js/reader.js`

### Multi-Origin Local TTS Service Discovery & Fallback Cache
- **Ngày**: 2026-10-29
- **Chi tiết**: Cơ chế khám phá mô hình giọng đọc offline tại runtime: client tự động thăm dò danh sách model từ local daemon server qua nhiều origin (`http://127.0.0.1:8080/api/tts/status`, relative `/api/tts/status`, `localhost`) với `AbortController` timeout (1.2s). Đồng thời lưu cache danh sách vào `localStorage` để ứng dụng nhận diện ngay 25 mô hình khi mở từ `file:///` hoặc khi offline.
- **Files liên quan**: `web-reader/js/voice-catalog.js`, `web-reader/server.py`

### Centralized Offline Voice Catalog & Comprehensive Vietnamese Voice Models
- **Ngày**: 2026-08-24
- **Chi tiết**: Metadata giọng AI offline tập trung tại `TtsVoiceCatalog` với 25 model giọng đọc Tiếng Việt ONNX từ repo HuggingFace `doof-ferb/nghitts-copy`. Mỗi model định nghĩa rõ `voiceId`, `displayName`, `language`, `downloadSize`, và `onnxFileName`. Toàn bộ model dùng chung `tokens.txt` và bộ ngữ âm `espeak-ng-data`. Trạng thái đã tải (`isDownloaded`) được tính tại runtime từ filesystem.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/TtsVoiceCatalog.kt`, `core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt`, `core/tts/src/test/java/com/epubpro/core/tts/TtsVoiceCatalogTest.kt`

### Foreground Service with MediaSession & Audio Focus
- **Ngày**: 2026-07-27
- **Chi tiết**: Sử dụng `ForegroundService` kết hợp `MediaSessionCompat` và `NotificationCompat.MediaStyle` để âm thanh phát liên tục khi tắt màn hình hoặc chuyển app. Tự động nhận diện tai nghe Bluetooth, xử lý `AudioFocusRequest` dừng TTS khi có cuộc gọi đến.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsMediaSessionManager.kt`

### HTML Paragraph Chunking & WebView Highlight Sync
- **Ngày**: 2026-07-27
- **Chi tiết**: `TtsTextParser` phân tách mã HTML của chương thành danh sách `TtsChunk` theo chỉ số đoạn (`paragraphIndex`). Khi TTS phát đến đâu, gửi event qua WebView JS Bridge `window.epubproHighlightTtsParagraph(index)` để thêm CSS class `.tts-active-paragraph` tô sáng và gọi `scrollIntoView({ behavior: 'smooth', block: 'center' })`.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsTextParser.kt`, `core/reader/src/main/java/com/epubpro/core/reader/style/CssInjector.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderScreen.kt`

### Sherpa-ONNX Local AI TTS Engine Integration & Offline Model Download
- **Ngày**: 2026-07-31
- **Chi tiết**: Tích hợp Sherpa-ONNX qua local `.aar` (`sherpa-onnx-1.13.4.aar`). Tải giọng đọc Tiếng Việt offline (Ngọc Ngạn `ngocngan3701.onnx`, Quang Minh `minhquang.onnx`) và `tokens.txt` trực tiếp từ HuggingFace `doof-ferb/nghitts-copy`. Khởi tạo `OfflineTts` với `assetManager = null` để load trực tiếp file từ bộ nhớ máy (`newFromFile`).
- **Files liên quan**: `core/tts/build.gradle.kts`, `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`, `core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt`

### TTS Engine Ownership & Async Readiness
- **Ngày**: 2026-08-06
- **Chi tiết**: `TtsService` sở hữu trạng thái phát, index và vòng đời engine của Reader; màn Settings preview phải dùng instance Sherpa riêng để `release()` không phá engine đang đọc. Wrapper của Android TTS và Sherpa giữ yêu cầu phát tạm thời trong lúc khởi tạo, rồi phát sau callback ready để lần bấm Play đầu tiên không bị mất. Native `OfflineTts.generate()` là lời gọi blocking và không hủy giữa chừng, nên mọi lần generate trên cùng engine phải được tuần tự hóa bằng `Mutex`.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/AndroidNativeTtsEngine.kt`, `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`

### HtmlNormalizer Auto-Wrapping Raw Text into <p> Tags
- **Ngày**: 2026-08-07
- **Chi tiết**: Khi nạp chương EPUB thiếu thẻ đoạn văn (sách convert từ web/TXT qua `GetTextFromHtml` không có `<p>`), `HtmlNormalizer` tự động phân tách văn bản nối bởi `<br>` hoặc dấu xuống dòng thành các thẻ `<p>` riêng biệt. Giúp cả WebView hiển thị đẹp, TTS chia đoạn mượt và Highlight tô sáng đúng từng dòng thay vì tô sáng cả trang `<body>`.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/engine/HtmlNormalizer.kt`, `core/reader/src/main/java/com/epubpro/core/reader/engine/EpubEngine.kt`

---

### Centralized Offline Voice Catalog
- **Ngày**: 2026-08-10
- **Chi tiết**: Metadata giọng AI offline phải có một nguồn duy nhất gồm voiceId, tên hiển thị, ngôn ngữ, dung lượng, tên file ONNX và URL tải. TtsVoiceCatalog được dùng chung bởi màn Cài đặt, Reader, downloader và Piper wrapper để không còn danh sách hoặc mapping riêng bị lệch nhau. Trạng thái đã tải không thuộc catalog tĩnh mà được tính tại runtime.
- **Files liên quan**: core/tts/src/main/java/com/epubpro/core/tts/TtsVoiceCatalog.kt, core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt

### Capability-Aware Dual Engine Configuration
- **Ngày**: 2026-08-10
- **Chi tiết**: Android Native và AI Offline có khả năng khác nhau. Native hỗ trợ locale, system voice, tốc độ và cao độ; Piper/Sherpa hiện chỉ hỗ trợ giọng Việt trong catalog và tốc độ, không hỗ trợ cao độ. Domain normalization, engine API và UI phải dùng cùng ma trận khả năng: AI ép ngôn ngữ vi, cao độ 1.0, ẩn điều khiển không có tác dụng; Native lấy danh sách voice theo locale thật của thiết bị.
- **Files liên quan**: domain/src/main/java/com/epubpro/domain/model/TtsModels.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/TtsEngine.kt, feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsScreen.kt

### Persisted Settings Must Represent a Playable Configuration
- **Ngày**: 2026-08-10
- **Chi tiết**: voiceId = null là trạng thái hợp lệ khi người dùng chưa cấu hình AI, nhưng isAiVoice = true chỉ được lưu khi voiceId tồn tại trong catalog. Việc bật tab AI rồi chưa chọn model là draft cục bộ của màn hình, không phải cấu hình playback. Khi đọc dữ liệu cũ không đầy đủ, tự phục hồi về Native với voiceId = null để lần mở sau luôn có cấu hình dùng được.
- **Files liên quan**: feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsViewModel.kt, core/storage/src/main/java/com/epubpro/core/storage/TtsPreferencesManager.kt


### Resilient TTS Foreground Playback Session
- **Ngày**: 2026-08-11
- **Chi tiết**: `TtsService` là chủ sở hữu duy nhất của playback, notification và bubble. Khi bubble tắt, Stop dọn phiên như media service thông thường. Khi bubble bật và còn quyền overlay, Stop chỉ giải phóng engine, AudioFocus, timer và chuyển sang Idle nhưng giữ notification/bubble; `START_STICKY` chỉ dùng trong trạng thái này. Null restart không tự phát mà chỉ phục hồi snapshot tối thiểu. Mỗi lần đổi loại foreground phải có fail-safe vì Android 12+ có thể từ chối promote khi ứng dụng ở nền.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/TtsAudioFocusController.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/TtsChapterPlaybackCoordinator.kt

### System Overlay Audio Bubble
- **Ngày**: 2026-08-11
- **Chi tiết**: Bubble dùng `TYPE_APPLICATION_OVERLAY` nhưng không tạo playback stack riêng. UI overlay chỉ gửi command và render model bất biến từ `TtsService`; reducer quyết định Disabled/Hidden/Collapsed/Expanded dựa trên toggle, quyền, foreground app, khóa màn hình và cờ ẩn phiên hiện tại. Preference vị trí cùng snapshot playback được lưu private, atomic; snapshot chỉ chứa cursor và timeline, không lưu text/HTML. Quyền overlay được theo dõi bằng `AppOpsManager`, không dùng polling khi Idle.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/bubble/TtsBubbleRuntime.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/bubble/TtsBubbleOverlayController.kt, core/storage/src/main/java/com/epubpro/core/storage/TtsPlaybackSnapshotStore.kt

### Service Owns Chapter Transition Commands
- **Ngày**: 2026-08-13
- **Chi tiết**: `TtsService` là chủ sở hữu duy nhất của việc chuyển chunk/chapter và `playbackGeneration`. `ReaderViewModel` chỉ đồng bộ chapter hiển thị sau khi service phát `Preparing`/`Playing` của chapter mới. UI không được suy diễn một command Play từ state chuyển tiếp như `Loading`; nếu cần auto-start theo ý định người dùng, phải dùng event/pending intent riêng, có thể consume đúng một lần.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderScreen.kt`

---
## Bugs & Solutions

### Dropdown Giọng AI hiển thị sai model so với audio đang phát do IndexedDB blocked
- **Ngày**: 2026-10-29
- **Vấn đề**: Player đang phát giọng Quang Minh (`selectedAiVoiceId = "quang_minh"`), thông tin trích đoạn hiển thị đúng "Giọng: Quang Minh", nhưng dropdown Giọng AI lại hiển thị "🎙️ Ngọc Ngạn".
- **Root cause**: `TtsVoiceStorage.init()` nâng version IndexedDB từ 1 lên 2 trong khi `EpubStorage` đang giữ connection version 1 mở, làm request `indexedDB.open` bị blocked vĩnh viễn. Hàm `initAiVoiceCatalog()` bị treo trước khi gọi `initTtsVoices()`, khiến dropdown giữ nguyên option HTML tĩnh ban đầu là Ngọc Ngạn.
- **Fix**: Tách riêng DB `EpubPro_Voice_DB` (v1) có xử lý `onversionchange`, thêm các option mặc định vào HTML tĩnh, gọi `initTtsVoices()` đồng bộ khi khởi tạo và ép đồng bộ `this.dom.ttsFullVoiceSelect.value = this.tts.selectedAiVoiceId` trong `updateTtsUi()` và `openFullTtsPlayer()`.
- **Files liên quan**: `web-reader/js/reader.js`, `web-reader/js/voice-catalog.js`, `web-reader/index.html`

### Card giọng đọc vừa hiển thị nhãn ĐANG CHỌN vừa có nút Tải về (60.6 MB)
- **Ngày**: 2026-10-29
- **Vấn đề**: Trong modal Kho Giọng AI, card Quang Minh có viền cam và nhãn "ĐANG CHỌN", nhưng nút hành động bên dưới lại là nút màu đỏ "📥 Tải về (60.6 MB)" thay vì "✓ Đang dùng".
- **Root cause**: `getDownloadedVoiceIds()` fetch thất bại do mở qua `file:///` không gọi được relative URL `/api/tts/status`, fallback về chỉ có `ngoc_ngan`. Biến `isDownloaded` của Quang Minh bị tính là `false`, trong khi `isSelected` là `true`.
- **Fix**: Áp dụng quy tắc invariant `isDownloaded = isSelected || downloadedIds.has(voice.id)`; đồng thời kết nối trực tiếp `http://127.0.0.1:8080/api/tts/status` để nạp đủ 25 model đã có sẵn trên máy chủ local vào `downloadedVoiceIds`.
- **Files liên quan**: `web-reader/js/reader.js`, `web-reader/js/voice-catalog.js`

### Đoạn cuối chapter bị phát lặp và không chuyển sang chapter kế tiếp
- **Ngày**: 2026-08-13
- **Vấn đề**: Khi Reader đang mở, TTS đôi lúc phát lặp vô hạn đoạn cuối của chapter cũ.
- **Root cause**: `TtsService` phát `TtsPlayerState.Loading` trong lúc tự chuyển chapter. `ReaderScreen` có `LaunchedEffect` coi mọi `Loading` là yêu cầu gọi `startTtsServicePlayback()`, nên nạp lại HTML chapter cũ. `loadContent()` tăng `playbackGeneration`, khiến coroutine chuyển chapter mới tự loại bỏ và vòng lặp tái diễn.
- **Fix**: Xóa side effect phát lại dựa trên `TtsPlayerState.Loading`. Các nút người dùng gọi command trực tiếp; UI chỉ gọi `onChapterSelected(nextChapter)` để đồng bộ WebView khi service phát `Preparing`/`Playing` của chapter mới.
- **Files liên quan**: `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderScreen.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`

### Highlight TTS bị lệch đoạn văn so với âm thanh đang đọc
- **Ngày**: 2026-07-31
- **Vấn đề**: Hiệu ứng tô màu đoạn văn (highlight) nhảy sai dòng so với âm thanh.
- **Root cause**: Android dùng Regex tự tách các tag (bắt nhầm cả `<div>`, `<section>`). Còn JS WebView dùng `querySelectorAll` chỉ chọn `p, h1..h6, li, blockquote`. Sự chênh lệch số lượng Element làm `paragraphIndex` giữa Android và JS không khớp 1:1.
- **Fix**: Tích hợp `Jsoup` vào Android. Gọi `document.select("p, h1, h2, h3, h4, h5, h6, li, blockquote")` hệt như JS để đảm bảo số thứ tự index luôn chính xác tuyệt đối ở 2 môi trường.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsTextParser.kt`

### Lỗi mất chữ và sai lệch vị trí đọc TTS khi HTML chứa mã hóa Hex/Dec Unicode Entity
- **Ngày**: 2026-07-31
- **Vấn đề**: Các từ chứa Unicode entity (như `L&#xFD; M&#x1ED9;c &#x110;i&#x1EC1;n`) hiển thị nguyên chuỗi mã hóa thô trong trình phát TTS thay vì tiếng Việt chuẩn có dấu (`Lý Mộc Điền`).
- **Root cause**: Hàm strip HTML thô trước đây không decode mã Hex (`&#x...;`) và Dec (`&#...;`) Unicode point trước khi gửi sang TTS engine.
- **Fix**: Sử dụng `Jsoup.parse(htmlContent)` bóc tách văn bản qua `document.select("p, h1, h2, h3, h4, h5, h6, li, blockquote")`. Jsoup tự động decode toàn bộ HTML entities thành tiếng Việt chuẩn và đảm bảo chỉ số `paragraphIndex` trùng khớp 100% với `querySelectorAll` trong JavaScript.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsTextParser.kt`

### Lỗi Unresolved reference / Builder Pattern không hợp lệ trên Sherpa-ONNX Java/Kotlin Bindings
- **Ngày**: 2026-07-31
- **Vấn đề**: `SherpaTtsEngine.kt` gặp lỗi compile khi dùng Builder Pattern (`OfflineTtsVitsModelConfig.builder()`) và gọi `sampleRate` như một property.
- **Root cause**: Trong `sherpa-onnx-1.13.4.aar`, các class config (`OfflineTtsVitsModelConfig`, `OfflineTtsModelConfig`, `OfflineTtsConfig`) là Kotlin data classes sử dụng constructor mặc định + property setters (hoặc constructor đầy đủ), không cung cấp Builder pattern. Đồng thời `sampleRate()` là phương thức (function).
- **Fix**: Khởi tạo các class config bằng no-arg constructor và gán thuộc tính (`vitsConfig.model = ...`, `vitsConfig.tokens = ...`), gọi `sampleRate()` dưới dạng hàm.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`

### Lỗi Unresolved reference: getJsonPath khi chuyển từ Piper sang Sherpa-ONNX
- **Ngày**: 2026-07-31
- **Vấn đề**: `PiperTtsEngineWrapper.kt` gặp lỗi compile `Unresolved reference: getJsonPath`.
- **Root cause**: `VoiceModelDownloader.kt` đã đổi tên và cấu trúc file từ `.json` (Piper) sang `tokens.txt` (`getTokensPath`) cho phù hợp với Sherpa-ONNX.
- **Fix**: Cập nhật `PiperTtsEngineWrapper.kt` đổi `getJsonPath()` thành `getTokensPath()` và truyền tham số dạng named parameters cho `SherpaTtsEngine.initialize(onnxPath = ..., tokensPath = ...)`.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt`, `core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt`

### Lỗi Failed to set eSpeak-ng voice khi gọi OfflineTts.generate()
- **Ngày**: 2026-08-03
- **Vấn đề**: Khi bấm Nghe thử, C++ nổ lỗi `Failed to set eSpeak-ng voice` và văng app (SIGABRT).
- **Root cause**: Thư viện `espeak-ng` tìm kiếm file voice định nghĩa ngôn ngữ Tiếng Việt theo nhiều đường dẫn khác nhau như `lang/aav/vi`, `voices/vi`, và `voices/!v/vi`. Nếu thiếu bất kỳ đường dẫn nào trong số này, `espeak_SetVoiceByName("vi")` sẽ thất bại và quăng ra `std::runtime_error`.
- **Fix**: Trong `VoiceModelDownloader.kt`, sau khi tải `lang/aav/vi`, tự động thực hiện hàm `ensureVoiceAlias()` tạo bản sao alias đồng thời tại `espeak-ng-data/voices/vi` và `espeak-ng-data/voices/!v/vi`. Cập nhật `isEspeakDataReady()` kiểm tra cả 2 vị trí này.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt`

### Lỗi Trễ UI Sau Khi Đọc Xong Nút "Đang phát..." Mới Đổi Thành "Nghe thử"
- **Ngày**: 2026-08-03
- **Vấn đề**: Sau khi loa đọc hết câu văn, nút bấm UI phải chờ rất lâu (bằng đúng thời lượng audio) mới quay lại trạng thái "Nghe thử".
- **Root cause**: `AudioTrack.write()` ở chế độ `MODE_STREAM` của Android đã là một hàm đồng bộ tự dừng thread trong thời gian phát âm thanh. Việc gọi thêm `delay(durationMs)` phía sau `track.write()` đã làm thời gian chờ bị **nhân đôi** (2x duration).
- **Fix**: Bỏ hàm `delay(durationMs)` thừa trong `SherpaTtsEngine.speak()`, chỉ giữ lại `delay(100L)` nhỏ để flush audio. UI lập tức đổi trạng thái ngay khi loa kết thúc đọc.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`

### Lỗi SIGSEGV (SEGV_MAPERR fault addr 0x0) Khi Bấm Tạm Dừng Rồi Bấm Nghe Tiếp
- **Ngày**: 2026-08-03
- **Vấn đề**: Đang nghe đọc sách, bấm Tạm dừng rồi bấm Nghe tiếp làm app văng với lỗi native crash `Fatal signal 11 (SIGSEGV)`.
- **Root cause**: `PiperTtsEngineWrapper.pause()` và `stop()` trước đó để trống (empty). Khi tạm dừng rồi bấm nghe tiếp, coroutine `speak()` cũ vẫn đang chạy ghi PCM vào `AudioTrack` trên background thread, song song đó coroutine `speak()` mới được tạo ra. Hai luồng cùng truy cập đồng thời vào native `AudioTrack` và `OfflineTts` C++ object dẫn đến va chạm vùng nhớ (Race Condition / Null Pointer dereference).
- **Fix**:
  1. Trong `PiperTtsEngineWrapper`, lưu `speakJob: Job?`. Khi gọi `pause()` hoặc `stop()`, thực hiện `speakJob?.cancel()` ngay lập tức để hủy job coroutine cũ và gọi `sherpaTtsEngine.stop()`.
  2. Trong `SherpaTtsEngine.speak()`, thực hiện ghi PCM vào `AudioTrack` theo từng khối nhỏ 4KB (`chunkSize = 4096`) và kiểm tra `coroutineContext.isActive` trong mỗi vòng lặp. Nếu bị cancel, vòng lặp dừng ngay lập tức và xả buffer `AudioTrack.pause() + flush()`.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt`, `core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt`

### Nút Next/Previous bị kẹt hoặc phát lại đoạn cũ khi qua ranh giới chương
- **Ngày**: 2026-08-11
- **Vấn đề**: Next/Previous liên tiếp trong lúc synthesize có thể bị bỏ qua; ở chunk đầu/cuối, lệnh không chuyển được sang chương kế/trước; chunk rỗng còn tạo vòng coroutine và trạng thái không ổn định.
- **Root cause**: Tác vụ chuyển chương bất đồng bộ vẫn để player ở trạng thái Playing, nhánh index âm trả về sớm, và callback/chuỗi xử lý cũ tiếp tục ghi đè index mới.
- **Fix**: Đặt Loading trước khi invalidate/await, bỏ qua lệnh điều hướng mới trong lúc chuyển chương, kiểm tra generation sau mỗi suspend boundary. Duyệt và bỏ qua chunk rỗng trong cùng lần phát; xử lý cả hai hướng qua chương kế/trước và khôi phục index cũ nếu không có chương trước hợp lệ.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsChapterPlaybackCoordinator.kt`

### Highlight tô sáng cả trang thay vì từng đoạn văn đang đọc
- **Ngày**: 2026-08-07
- **Vấn đề**: Khi phát TTS trên sách EPUB convert (như tool `GetTextFromHtml`), toàn bộ màn hình (`<body>`) bị bôi màu đỏ highlight cùng lúc.
- **Root cause**: Chương sách không có thẻ `<p>`, toàn bộ văn bản nằm trực tiếp trong `<body>` phân tách bằng `<br>`. `<body>` bị nhận diện là Block Element duy nhất, nên khi highlight `index=0`, thẻ `<body>` bị tô đỏ toàn bộ.
- **Fix**: Thêm `HtmlNormalizer` tự động phân tách văn bản thô bọc thành các thẻ `<p>` chuẩn khi nạp chương trong `EpubEngine.loadChapterHtml()`. Sử dụng thuật toán `TreeWalker` đồng bộ 100% giữa Kotlin (`TtsTextParser.kt`) và JavaScript (`CssInjector.kt`) để bắt đúng index từng đoạn văn.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/engine/HtmlNormalizer.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsTextParser.kt`, `core/reader/src/main/java/com/epubpro/core/reader/style/CssInjector.kt`

---

### AI Offline tự chọn cứng giọng ngoc_ngan
- **Ngày**: 2026-08-10
- **Vấn đề**: Mở chế độ AI hoặc tải model khi chưa chọn giọng vẫn ngầm dùng Ngọc Ngạn, trái với yêu cầu mặc định để trống.
- **Root cause**: Giá trị fallback xuất hiện ở nhiều tầng như settings.voiceId ?: ngoc_ngan và currentVoiceId = ngoc_ngan.
- **Fix**: Giữ voiceId nullable xuyên suốt từ preferences, ViewModel, service đến engine; không download, preview hoặc playback nếu chưa chọn model. Không gán model đầu tiên trong catalog làm mặc định.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt, feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsViewModel.kt

### Quay lại màn cài đặt liên tục xuất hiện chưa chọn giọng ở AI Offline
- **Ngày**: 2026-08-10
- **Vấn đề**: Người dùng chỉ chuyển sang AI rồi back; lần mở sau ứng dụng khôi phục isAiVoice = true nhưng voiceId = null và hiển thị cấu hình lỗi.
- **Root cause**: Handler đổi engine tự động lưu ngay một tổ hợp chưa hoàn chỉnh trước khi người dùng chọn model.
- **Fix**: Không persist draft AI thiếu model. Chỉ lưu khi model hợp lệ; nếu preferences cũ chứa tổ hợp không hợp lệ thì migrate về Native. Nhãn trống dùng lời kêu gọi hành động “Chọn giọng AI” thay vì mô tả lỗi “Chưa chọn giọng”.
- **Files liên quan**: feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsViewModel.kt, feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsScreen.kt

### UI cho phép chỉnh cao độ và ngôn ngữ mà AI engine không hỗ trợ
- **Ngày**: 2026-08-10
- **Vấn đề**: Thanh cao độ và lựa chọn tiếng Anh vẫn xuất hiện trong AI Offline nhưng thay đổi không có tác dụng hoặc dẫn đến cấu hình không thể đọc.
- **Root cause**: UI dùng chung controls của Native mà không xét capability của engine.
- **Fix**: Ẩn cao độ ở AI, khóa ngôn ngữ theo catalog hiện có, đồng thời normalize AI pitch về 1.0 và language về vi. Ràng buộc phải tồn tại ở domain/engine, không chỉ ở giao diện.
- **Files liên quan**: domain/src/main/java/com/epubpro/domain/model/TtsModels.kt, feature/reader/src/main/java/com/epubpro/feature/reader/components/TtsSetupBottomSheet.kt

### Danh sách và URL model AI bị lệch giữa các màn hình
- **Ngày**: 2026-08-10
- **Vấn đề**: Profile, Reader, downloader và Piper khai báo giọng riêng, gây thiếu model, URL sai hoặc tên hiển thị không đồng nhất.
- **Root cause**: Metadata model bị sao chép ở nhiều module.
- **Fix**: Chuyển toàn bộ metadata tĩnh vào TtsVoiceCatalog; các consumer chỉ truy vấn catalog và ghép thêm trạng thái tải runtime. Thêm unit test kiểm tra ID, file ONNX và URL duy nhất/hợp lệ.
- **Files liên quan**: core/tts/src/main/java/com/epubpro/core/tts/TtsVoiceCatalog.kt, core/tts/src/test/java/com/epubpro/core/tts/TtsVoiceCatalogTest.kt


### Thanh tiến trình notification đứng yên khi đang phát TTS
- **Ngày**: 2026-08-10
- **Vấn đề**: Notification MediaStyle hiển thị thanh thời lượng nhưng vị trí không chạy, đặc biệt với AI Offline.
- **Root cause**: PlaybackState chỉ được cập nhật khi đổi câu/trạng thái; MediaSession không tự suy ra tiến trình nếu position và update time không được phát lại định kỳ. Callback AI trước đây còn báo Playing trước khi PCM thực sự bắt đầu.
- **Fix**: Cập nhật PlaybackState mỗi giây trong khi Playing; thêm trạng thái Preparing và chỉ bắt đầu đồng hồ khi AudioTrack chuẩn bị phát PCM. Pause giữ vị trí hiện tại, chuyển câu dựng lại timeline, callback cũ bị loại bằng generation token.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/TtsMediaSessionManager.kt, core/tts/src/main/java/com/epubpro/core/tts/SherpaTtsEngine.kt


### Stop xong nhưng coroutine auto-next có thể phát lại
- **Ngày**: 2026-08-10
- **Vấn đề**: Người dùng bấm Stop đúng lúc service đang chuẩn bị chương kế tiếp nhưng audio hoặc trạng thái lỗi có thể xuất hiện lại sau đó.
- **Root cause**: Stop chỉ hủy engine và tăng generation của callback câu; coroutine chuyển chương đã qua điểm await vẫn có thể trả kết quả, ghi chunks mới và gọi Play. Một request prepare sách cũ cũng có thể hoàn tất muộn và ghi đè session mới.
- **Fix**: Truyền expected generation vào tác vụ auto-next, kiểm tra lại sau mọi suspend boundary trước khi ghi state hoặc phát audio. Coordinator dùng AtomicLong và session volatile để chỉ request prepare mới nhất được commit; clear cũng tăng generation. Stop vì thế vô hiệu hóa cả callback engine lẫn I/O đang bay.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/TtsChapterPlaybackCoordinator.kt

### Stop ở Idle không hủy sleep timer cũ
- **Ngày**: 2026-08-11
- **Vấn đề**: Đặt timer khi Idle rồi bấm Stop có thể để job cũ dừng nhầm phiên phát kế tiếp.
- **Root cause**: `stopSession()` return sớm ở nhánh Idle trước khi gọi `resetSleepTimer()`.
- **Fix**: Reset timer phải là bước đầu tiên của Stop, trước mọi early-return; finish playback và đường fail-safe foreground cũng phải dùng cùng cleanup.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt

### Quyền và deep-link bị kẹt sau process death
- **Ngày**: 2026-08-11
- **Vấn đề**: Toggle bubble có thể hiển thị ON giả, notification mở Reader nhưng không mở full player, hoặc Intent mới bị bỏ qua sau khi Android tạo lại process.
- **Root cause**: Pending quyền được đặt quá sớm; trạng thái player chỉ nằm trong `UiState`; Activity dùng một Boolean consumed không gắn với nội dung Intent.
- **Fix**: Chỉ persist pending ở stage overlay và reconcile khi Resume; lưu visibility player vào `SavedStateHandle`; consume request bằng cách dispatch rồi xóa action/extras khỏi Intent. Trạng thái bền và one-shot effect phải được tách riêng.
- **Files liên quan**: app/src/main/java/com/epubpro/app/MainActivity.kt, feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsScreen.kt, feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt

---
## How-To

### Đồng bộ danh mục giọng đọc AI giữa Local Python Server và Web Reader
- **Ngày**: 2026-10-29
- **Bước thực hiện**:
  1. Kiểm tra local daemon server tại `http://127.0.0.1:8080/api/tts/status` trả về mảng `downloaded_models`.
  2. Tại client, `TtsVoiceStorage.getDownloadedVoiceIds()` thăm dò endpoint với timeout 1.2s và lưu kết quả vào `localStorage.epubpro_downloaded_voices`.
  3. Bổ sung `selectedAiVoiceId` vào `downloadedVoiceIds` để đảm bảo model đang phát luôn sẵn sàng.
  4. Cập nhật huy hiệu `aiVoiceBadgeCount`, render dropdown nhóm `⭐ Giọng AI Đã Tải Về` và gán `select.value`.
  5. Khi đổi giọng, cập nhật đồng thời state, localStorage, audio source và UI player.
- **Files liên quan**: `web-reader/js/voice-catalog.js`, `web-reader/js/reader.js`, `web-reader/server.py`

### Chẩn đoán vòng lặp state-effect giữa Reader và TTS Service
- **Ngày**: 2026-08-13
- **Bước thực hiện**:
  1. Lập timeline từ callback `onChunkDone` qua `advanceToNextChapter`, state phát ra và observer UI.
  2. Tìm mọi `LaunchedEffect`/collector phản ứng với state chuyển tiếp và kiểm tra chúng có phát command ngược về service hay không.
  3. Theo dõi nơi tăng `playbackGeneration`; nếu command UI tăng generation trong lúc transition đang await, coroutine hợp lệ sẽ bị loại.
  4. Kiểm tra boundary khi Reader foreground, background và service chưa bind vì feedback loop thường phụ thuộc lifecycle UI.
  5. Test final chunk → `Loading` → chapter mới; xác nhận `loadContent()` không bị gọi lại với chapter cũ.
- **Files liên quan**: `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderScreen.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`

### Đồng bộ vị trí bắt đầu nghe TTS với đoạn đang đọc dở trên màn hình
- **Ngày**: 2026-07-31
- **Bước thực hiện**:
  1. Trong JS (`CssInjector.kt`), tạo hàm `getFirstVisibleParagraphIndex()` dùng `getBoundingClientRect()` để tính đoạn văn đầu tiên đang lọt vào khung màn hình khi User lướt (scroll).
  2. Truyền `visibleIndex` qua cầu nối `ReaderJsBridge.onPageChanged` về cho Android mỗi khi cuộn trang.
  3. Ở Android, lưu `visibleIndex` xuống `SharedPreferences` cho từng ID Sách và Chương. (Cũng lưu lại khi TTS tự động đọc sang câu mới).
  4. Lần tới bấm Play TTS, lấy index đã lưu làm `startIndex` thay vì bằng 0, giúp nghe tiếp liền mạch chính xác ngay câu chữ đang nhìn thấy.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/style/CssInjector.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`

### Chẩn đoán lỗi chuyển đoạn TTS trên thiết bị ADB
- **Ngày**: 2026-08-06
- **Bước thực hiện**:
  1. Xóa logcat, mở đúng chương và phát TTS; đối chiếu tổng số đoạn trên UI với log tag `EpubProTTS`.
  2. Nếu chương chỉ có tiêu đề hoặc `0/0`, kiểm tra tỷ lệ text do selector Jsoup thu được so với `document.body().text()`.
  3. Bấm Next khi đang synthesize; xác nhận audio cũ dừng trước khi request đoạn mới bắt đầu và callback cũ không đổi index.
  4. Chạy unit test parser với HTML dùng `p` và HTML chỉ dùng `div`/`br`, sau đó build APK, cài ADB và smoke-test lại.
- **Files liên quan**: `core/reader/src/test/java/com/epubpro/core/reader/tts/TtsTextParserTest.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`

---

### Thêm một giọng AI Offline mới
- **Ngày**: 2026-08-10
- **Bước thực hiện**:
  1. Thêm đúng một entry vào TtsVoiceCatalog với ID ổn định, ngôn ngữ, tên file và URL.
  2. Không thêm mapping riêng vào Profile, Reader hay Piper; các lớp này phải tự nhận model từ catalog.
  3. Bổ sung test uniqueness và mapping URL/file.
  4. Kiểm tra trạng thái chưa tải, tải model, preview, chọn làm cấu hình và playback trong Reader.
- **Files liên quan**: core/tts/src/main/java/com/epubpro/core/tts/TtsVoiceCatalog.kt, core/tts/src/test/java/com/epubpro/core/tts/TtsVoiceCatalogTest.kt

### Kiểm thử thay đổi cấu hình TTS
- **Ngày**: 2026-08-10
- **Bước thực hiện**:
  1. Chạy unit test domain normalization, catalog và reader parser.
  2. Compile feature:profile, feature:reader và cuối cùng app để bắt lỗi wiring giữa module.
  3. Smoke-test Native theo locale/voice thật của máy.
  4. Với AI, thử cả chưa chọn, chưa tải, đã tải và đổi model.
  5. Lặp chuỗi mở Settings → chọn tab AI → back → mở lại để chắc chắn draft không bị persist.
- **Lệnh gợi ý**: ./gradlew :domain:testDebugUnitTest :core:tts:testDebugUnitTest :core:reader:testDebugUnitTest :feature:profile:compileDebugKotlin :feature:reader:compileDebugKotlin :app:compileDebugKotlin


### Smoke-test vòng đời playback TTS
- **Ngày**: 2026-08-11
- **Bước thực hiện**:
  1. Phát Native và AI; xác nhận notification chuyển Preparing sang Playing và progress cập nhật mỗi giây.
  2. Thử Home, khóa màn hình, đổi app và vuốt Recent; âm thanh phải tiếp tục.
  3. Thử Pause/Resume, mất AudioFocus tạm thời, cuộc gọi và rút tai nghe; chỉ trường hợp hệ thống tạm dừng mới tự resume khi lấy lại focus.
  4. Đổi tốc độ/giọng khi đang phát; câu hiện tại phải khởi động lại với cấu hình mới.
  5. Bấm Stop lần lượt trên app và notification; service, audio và notification phải dừng hoàn toàn.
  6. Để hết chương; kiểm tra tự chuyển chương, AI cache không hợp lệ fallback nội dung gốc và sleep timer cuối chương.
  7. Bật bubble theo thứ tự notification permission rồi overlay permission; kiểm tra cả grant, deny, process death giữa hai bước và revoke trực tiếp trong Android Settings.
  8. Kiểm tra Idle Play/Previous/Next khôi phục snapshot, Stop không autoplay, mở sách đúng chương/full player, vuốt Recent và mở app lại sau force-stop/reboot.
  9. Trên API 26/30/34, thử portrait/landscape, 3-button navigation, lockscreen và trường hợp foreground promotion bị hệ thống từ chối.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt, feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt

---
## Patterns

### Invariant Selected-Is-Downloaded Pattern
- **Ngày**: 2026-10-29
- **Chi tiết**: Khi người dùng đang chọn một model làm giọng đọc (`selectedAiVoiceId`), UI luôn đảm bảo `isDownloaded = true` và `downloadedVoiceIds.add(voiceId)`. Pattern này ngăn chặn triệt để trạng thái mâu thuẫn giao diện (card vừa có viền active "ĐANG CHỌN" vừa hiển thị nút kêu gọi "Tải về").
- **Ví dụ code**:
  ```javascript
  const isSelected = this.tts.selectedAiVoiceId === voice.id;
  const isDownloaded = isSelected || downloadedIds.has(voice.id);
  ```
- **Files liên quan**: `web-reader/js/reader.js`

### Multi-Origin Local Service Fallback Loop
- **Ngày**: 2026-10-29
- **Chi tiết**: Khi web app client cần tương tác với daemon service local (như Python TTS engine), duyệt danh sách các candidate URL (`http://127.0.0.1:PORT`, `/relative/path`, `http://localhost:PORT`) với `AbortController` timeout ngắn. Cache kết quả thành công vào `localStorage` làm fallback cho các lần mở ngoại tuyến hoặc mở qua giao thức `file:///`.
- **Files liên quan**: `web-reader/js/voice-catalog.js`

### Observable State Must Not Implicitly Reissue Playback Commands
- **Ngày**: 2026-08-13
- **Chi tiết**: State từ service (`Loading`, `Preparing`, `Playing`) là projection để UI render, không phải one-shot command. `LaunchedEffect(state)` không được gọi lại `loadContent()`/Play nếu state đó cũng có thể do service tự phát trong lifecycle nội bộ. Command phải bắt nguồn từ thao tác người dùng hoặc event riêng có identity/consume semantics; state observer chỉ đồng bộ giao diện. Pattern này tránh feedback loop, duplicate command và vô hiệu hóa generation token hợp lệ.
- **Files liên quan**: `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderScreen.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`

### DOM-Aligned Jsoup Parsing & Auto-Save Last TTS Paragraph Position
- **Ngày**: 2026-07-31
- **Chi tiết**: Kết hợp `Jsoup` ở phía Kotlin và `document.elementsFromPoint` / `querySelectorAll` ở phía WebView JS. Mỗi khi người dùng cuộn trang, JS phát `onPageChanged(page, totalPages, firstVisibleChunkIndex)` về `ReaderViewModel` để tự động lưu `lastTtsChunkIndex`. Khi bật phát Audio, TTS tự động bắt đầu ngay tại đoạn văn bản đang hiển thị trên màn hình.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsTextParser.kt`, `core/reader/src/main/java/com/epubpro/core/reader/style/CssInjector.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`

### Synchronized DOM TreeWalker Extraction for Kotlin & JS
- **Ngày**: 2026-08-07
- **Chi tiết**: Sử dụng thuật toán `TreeWalker` (JS) và `NodeVisitor` (Jsoup) để duyệt các TextNode theo đúng thứ tự hiển thị DOM thực tế rồi gom nhóm theo Block Element gần nhất. Cách này đảm bảo số lượng và chỉ số đoạn văn (`paragraphIndex`) khớp 100% tuyệt đối giữa Kotlin TTS Engine và JavaScript Highlight Bridge trong WebView.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsTextParser.kt`, `core/reader/src/main/java/com/epubpro/core/reader/style/CssInjector.kt`

### Single Source of Truth via Service StateFlow
- **Ngày**: 2026-07-27
- **Chi tiết**: `TtsService` nắm giữ `MutableStateFlow<TtsPlayerState>` duy nhất. `ReaderViewModel` và `ReaderScreen` lắng hệ flow này để cập nhật đồng bộ cho cả Fullscreen Player, Mini Player Bar, và vị trí highlight trong WebView.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt`

### Real-Time Audio Settings Synchronization via Preference Flow
- **Ngày**: 2026-07-31
- **Chi tiết**: `TtsPreferencesManager` phát ra `StateFlow<TtsSettings>` mỗi khi thay đổi cài đặt (giọng đọc, tốc độ, cao độ, bật/tắt AI voice). `TtsService` lắng nghe (`collect`) flow me để tự động cập nhật engine đang phát mà không cần restart Service hay truyền Intent thủ công từ UI Tab Cá nhân.
- **Files liên quan**: `core/storage/src/main/java/com/epubpro/core/storage/TtsPreferencesManager.kt`, `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsViewModel.kt`

### Atomic Temp File Downloading with Fallback Renaming
- **Ngày**: 2026-08-03
- **Chi tiết**: Khi tải file binary lớn (ONNX model, dict files), luôn tải về file `.tmp` trước. Dùng `renameTo()` và fallback sang `copyTo(overwrite = true) + delete()` để đảm bảo tính nguyên tử, không để lại file hỏng/rác khi gặp ngắt kết nối mạng.
- **Files liên quan**: `core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt`

### Playback Generation Token Pattern
- **Ngày**: 2026-08-06
- **Chi tiết**: Mỗi lệnh Play/Next/Previous/Seek tạo một generation mới. Callback hoàn tất chỉ được cập nhật state hoặc tự chuyển đoạn khi generation, expected index và chunk ID vẫn khớp lượt phát hiện tại. Lệnh điều hướng phải invalidate generation và stop engine trước khi đổi index. Pattern này tách ý định mới của người dùng khỏi callback bất đồng bộ đến muộn, tránh nhảy hai đoạn, quay lại đoạn cũ hoặc tự phát tiếp sau khi đã dừng.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`
### Normalize at Persistence and Playback Boundaries
- **Ngày**: 2026-08-10
- **Chi tiết**: Cấu hình đọc từ storage và cấu hình trước khi áp dụng vào engine đều phải đi qua cùng hàm normalization. Giới hạn tốc độ/cao độ, language và capability của AI được đặt ở domain để dữ liệu cũ, UI khác hoặc lời gọi trực tiếp không thể tạo trạng thái ngoài miền hợp lệ. Giữ nguyên key/type SharedPreferences để tương thích dữ liệu đã cài đặt.
- **Files liên quan**: domain/src/main/java/com/epubpro/domain/model/TtsModels.kt, core/storage/src/main/java/com/epubpro/core/storage/TtsPreferencesManager.kt

### Nullable Voice Selection End-to-End
- **Ngày**: 2026-08-10
- **Chi tiết**: Nếu sản phẩm cho phép mặc định “trống”, nullable phải được bảo toàn qua model, persistence, ViewModel, service và engine. Chỉ một fallback ở tầng thấp cũng tái tạo hành vi tự chọn model. Mỗi action cần kiểm tra điều kiện riêng: có thể xem catalog khi null, nhưng download/preview/playback phải yêu cầu một ID hợp lệ.
- **Files liên quan**: domain/src/main/java/com/epubpro/domain/model/TtsModels.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/TtsEngine.kt, core/reader/src/main/java/com/epubpro/core/reader/tts/PiperTtsEngineWrapper.kt

### Transactional Draft for Dependent Settings
- **Ngày**: 2026-08-10
- **Chi tiết**: Với các field phụ thuộc nhau như engine AI và model AI, không autosave từng thay đổi trung gian nếu tổ hợp đó chưa dùng được. Giữ draft trong UI, commit khi đạt invariant, và rollback/khôi phục cấu hình hợp lệ khi màn hình bị đóng. Pattern này tránh lỗi back/reopen và phù hợp cho mọi form có lựa chọn cha-con.
- **Files liên quan**: feature/profile/src/main/java/com/epubpro/feature/profile/audio/AudioSettingsViewModel.kt

### Static Catalog Metadata + Runtime Availability
- **Ngày**: 2026-08-10
- **Chi tiết**: Catalog chỉ chứa sự thật tĩnh của model; isDownloaded được tính từ filesystem/downloader tại thời điểm hiển thị. Nhờ tách hai lớp, cùng một catalog dùng được ở Settings và Reader nhưng UI vẫn phản ánh model bị xóa, tải dở hoặc vừa tải xong mà không làm biến đổi metadata dùng chung.
- **Files liên quan**: core/tts/src/main/java/com/epubpro/core/tts/TtsVoiceCatalog.kt, core/tts/src/main/java/com/epubpro/core/tts/VoiceModelDownloader.kt

### Preparing/Loading vẫn lộ text “Đang chuẩn bị giọng đọc” khi chuyển đoạn
- **Ngày**: 2026-08-11
- **Vấn đề**: Service đã chuyển sang Loading/Preparing nhưng Full Player hoặc Mini Player vẫn hiển thị câu placeholder thay vì đoạn hiện tại trong lúc tải audio của đoạn mới.
- **Root cause**: UI có nhánh fallback hard-code `tts_preparing_voice` cho mọi state ngoài Playing; vì vậy state chuyển tiếp che mất `currentChunk.text`, dù nội dung đoạn đã sẵn sàng.
- **Fix**: Giữ Preparing/Loading là trạng thái nội bộ để MediaSession và progress phản ánh việc synthesize; UI dùng `currentChunk.text` cho Preparing/Playing/Paused và projection hiện tại cho các state còn lại. Notification cũng fallback về text chunk hiện tại, không thay bằng placeholder.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/tts/TtsAudioPlayerScreen.kt`, `feature/reader/src/main/java/com/epubpro/feature/reader/tts/TtsMiniPlayerBar.kt`

### Event-Driven Overlay Availability and Durable Cursor State
- **Ngày**: 2026-08-11
- **Chi tiết**: Không giữ timer chỉ để kiểm tra quyền overlay khi Idle. Theo dõi `OPSTR_SYSTEM_ALERT_WINDOW` bằng `AppOpsManager.OnOpChangedListener`, chuyển thành `StateFlow` và chỉ đồng bộ service khi giá trị thực sự đổi. Playback snapshot dùng một record có version, commit đồng bộ để cập nhật logic nguyên tử; chỉ lưu book/chapter/paragraph/sentence/content-version/timeline. UI one-shot như Intent phải consume riêng, còn visibility cần sống qua process death phải nằm trong `SavedStateHandle`.
- **Files liên quan**: core/reader/src/main/java/com/epubpro/core/reader/tts/bubble/TtsOverlayPermissionTracker.kt, core/storage/src/main/java/com/epubpro/core/storage/TtsPlaybackSnapshotStore.kt, feature/reader/src/main/java/com/epubpro/feature/reader/ReaderViewModel.kt

### Explicit Chapter Transition State
- **Ngày**: 2026-08-11
- **Chi tiết**: Mọi chuyển chương phải phát tín hiệu Loading trước khi dừng engine và chờ I/O. State này khóa Previous/Next cạnh tranh, cập nhật MediaSession thành buffering nhưng vẫn giữ tiêu đề và text chunk cuối để notification/bubble không nhấp nháy placeholder. Sau khi load xong, chỉ generation hiện tại mới được commit index/chunk và chuyển sang play.
- **Files liên quan**: `core/reader/src/main/java/com/epubpro/core/reader/tts/TtsService.kt`
