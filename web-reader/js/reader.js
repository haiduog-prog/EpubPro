/**
 * Trình điều khiển đọc sách chính (EpubPro Web Reader Controller)
 * Đã loại bỏ toàn bộ dữ liệu demo/sách mẫu.
 * Hỗ trợ:
 * - Lưu trữ sách cá nhân và Lịch sử đọc qua IndexedDB + LocalStorage (Chuẩn ReadingHistoryRecord Android)
 * - HTML Paragraph Chunking & Highlight Synchronization (.tts-active-paragraph)
 * - TtsMiniPlayerBar & TtsAudioPlayerScreen Full Player với Soundwave Visualizer
 * - Quản lý tốc độ, giọng đọc Tiếng Việt, Sleep Timer (Hẹn giờ tắt)
 * - CSS Multi-Column Pagination & Vertical Scroll
 * - Kệ Sách Cá Nhân & Quản lý Lịch sử Đọc (Reading History)
 */

class EpubStorage {
  constructor() {
    this.dbName = "EpubPro_Web_DB";
    this.dbVersion = 1;
    this.db = null;
  }

  async init() {
    return new Promise((resolve) => {
      if (!window.indexedDB) {
        resolve(null);
        return;
      }
      const request = indexedDB.open(this.dbName, this.dbVersion);
      request.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains("books")) {
          db.createObjectStore("books", { keyPath: "id" });
        }
        if (!db.objectStoreNames.contains("history")) {
          db.createObjectStore("history", { keyPath: "bookTitle" });
        }
      };
      request.onsuccess = (e) => {
        this.db = e.target.result;
        this.db.onversionchange = () => {
          try { this.db.close(); } catch (err) {}
        };
        resolve(this.db);
      };
      request.onerror = (e) => {
        console.warn("Lỗi mở IndexedDB, fallback localStorage:", e);
        resolve(null);
      };
    });
  }

  async saveBook(book) {
    if (!this.db) return false;
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("books", "readwrite");
        tx.objectStore("books").put(book);
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
      } catch (e) {
        resolve(false);
      }
    });
  }

  async getBook(id) {
    if (!this.db) return null;
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("books", "readonly");
        const req = tx.objectStore("books").get(id);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => resolve(null);
      } catch (e) {
        resolve(null);
      }
    });
  }

  async getAllBooks() {
    if (!this.db) return [];
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("books", "readonly");
        const req = tx.objectStore("books").getAll();
        req.onsuccess = () => resolve(req.result || []);
        req.onerror = () => resolve([]);
      } catch (e) {
        resolve([]);
      }
    });
  }

  async deleteBook(id, bookTitle) {
    if (bookTitle) {
      this.deleteHistory(bookTitle);
    }
    if (!this.db) return false;
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("books", "readwrite");
        tx.objectStore("books").delete(id);
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
      } catch (e) {
        resolve(false);
      }
    });
  }

  getHistoryLocal() {
    const saved = localStorage.getItem("epubpro_reading_history");
    if (saved) {
      try {
        return JSON.parse(saved);
      } catch (e) {
        return [];
      }
    }
    return [];
  }

  async saveHistory(record) {
    // 1. Sao lưu localStorage
    try {
      const list = this.getHistoryLocal();
      const idx = list.findIndex(r => r.bookTitle === record.bookTitle);
      if (idx !== -1) {
        list[idx] = record;
      } else {
        list.unshift(record);
      }
      localStorage.setItem("epubpro_reading_history", JSON.stringify(list));
    } catch (e) {}

    // 2. Lưu IndexedDB
    if (!this.db) return;
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("history", "readwrite");
        tx.objectStore("history").put(record);
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
      } catch (e) {
        resolve(false);
      }
    });
  }

  async getHistory() {
    const local = this.getHistoryLocal();
    if (!this.db) return local;
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("history", "readonly");
        const req = tx.objectStore("history").getAll();
        req.onsuccess = () => {
          const results = req.result || [];
          results.sort((a, b) => (b.lastReadAt || 0) - (a.lastReadAt || 0));
          resolve(results.length > 0 ? results : local);
        };
        req.onerror = () => resolve(local);
      } catch (e) {
        resolve(local);
      }
    });
  }

  deleteHistory(bookTitle) {
    try {
      let list = this.getHistoryLocal();
      list = list.filter(r => r.bookTitle !== bookTitle);
      localStorage.setItem("epubpro_reading_history", JSON.stringify(list));
    } catch (e) {}

    if (!this.db) return;
    try {
      const tx = this.db.transaction("history", "readwrite");
      tx.objectStore("history").delete(bookTitle);
    } catch (e) {}
  }

  async clearHistory() {
    localStorage.removeItem("epubpro_reading_history");
    if (!this.db) return;
    return new Promise((resolve) => {
      try {
        const tx = this.db.transaction("history", "readwrite");
        tx.objectStore("history").clear();
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
      } catch (e) {
        resolve(false);
      }
    });
  }
}

class EpubProReader {
  constructor() {
    this.book = null;
    this.currentChapterIndex = 0;
    this.currentPageIndex = 0;
    this.totalPagesInChapter = 1;
    this.showControls = true;
    this.bookmarks = [];

    this.storage = new EpubStorage();
    this.customBooks = [];
    this.readingHistory = [];
    this.currentLibraryFilter = "all";
    this.librarySearchQuery = "";

    // Cài đặt đọc sách mặc định
    this.settings = this.loadSettings();

    // DOM Elements
    this.dom = {
      app: document.getElementById("reader-app"),
      topbar: document.getElementById("reader-topbar"),
      bottombar: document.getElementById("reader-bottombar"),
      stage: document.getElementById("reader-stage"),
      viewportPaged: document.getElementById("viewport-paged"),
      pagedContainer: document.getElementById("paged-container"),
      viewportScroll: document.getElementById("viewport-scroll"),
      scrollInner: document.getElementById("scroll-inner"),
      welcomeScreen: document.getElementById("welcome-reader-screen"),
      welcomeDropzone: document.getElementById("welcome-dropzone"),
      bookTitleDisplay: document.getElementById("book-title-display"),
      chapterSubtitleDisplay: document.getElementById("chapter-subtitle-display"),
      pageIndicator: document.getElementById("page-indicator"),
      progressSlider: document.getElementById("progress-slider"),
      progressPercent: document.getElementById("progress-percent"),
      btnPrevChapter: document.getElementById("btn-prev-chapter"),
      btnNextChapter: document.getElementById("btn-next-chapter"),
      btnPrevPage: document.getElementById("btn-prev-page"),
      btnNextPage: document.getElementById("btn-next-page"),

      // Drawers & Modals
      backdrop: document.getElementById("drawer-backdrop"),
      drawerToc: document.getElementById("drawer-toc"),
      drawerSettings: document.getElementById("drawer-settings"),
      modalUpload: document.getElementById("modal-upload"),
      modalBookmarks: document.getElementById("modal-bookmarks"),
      modalLibrary: document.getElementById("modal-library"),
      btnOpenLibrary: document.getElementById("btn-open-library"),
      btnCloseLibrary: document.getElementById("btn-close-library"),
      booksGrid: document.getElementById("books-grid"),
      librarySearchInput: document.getElementById("library-search-input"),
      btnLibraryUploadTrigger: document.getElementById("btn-library-upload-trigger"),
      btnClearHistory: document.getElementById("btn-clear-history"),
      tocList: document.getElementById("toc-list"),
      bookmarksList: document.getElementById("bookmarks-list"),
      toast: document.getElementById("toast-notice"),
      fileInput: document.getElementById("file-input"),
      dropZone: document.getElementById("drop-zone"),

      // TTS Mini Player Bar (TtsMiniPlayerBar.kt)
      ttsMiniBar: document.getElementById("tts-mini-bar"),
      ttsMiniPlayBtn: document.getElementById("tts-mini-play-btn"),
      ttsMiniPlayIcon: document.getElementById("tts-mini-play-icon"),
      ttsMiniInfoExpand: document.getElementById("tts-mini-info-expand"),
      ttsMiniStatusText: document.getElementById("tts-mini-status-text"),
      ttsMiniText: document.getElementById("tts-mini-text"),
      ttsMiniExpandBtn: document.getElementById("tts-mini-expand-btn"),
      ttsMiniCloseBtn: document.getElementById("tts-mini-close-btn"),

      // TTS Full Audio Player Screen (TtsAudioPlayerScreen.kt)
      ttsFullPlayer: document.getElementById("tts-full-player"),
      ttsFullMinimizeBtn: document.getElementById("tts-full-minimize-btn"),
      ttsFullBookTitle: document.getElementById("tts-full-book-title"),
      ttsFullBookAuthor: document.getElementById("tts-full-book-author"),
      ttsFullChapterTitle: document.getElementById("tts-full-chapter-title"),
      ttsFullChunkInfo: document.getElementById("tts-full-chunk-info"),
      ttsFullParagraphText: document.getElementById("tts-full-paragraph-text"),
      ttsAudioVisualizer: document.getElementById("tts-audio-visualizer"),
      ttsFullSeekSlider: document.getElementById("tts-full-seek-slider"),
      ttsFullTimeCurrent: document.getElementById("tts-full-time-current"),
      ttsFullTimeTotal: document.getElementById("tts-full-time-total"),
      ttsFullSkipPrev: document.getElementById("tts-full-skip-prev"),
      ttsFullPlayBtn: document.getElementById("tts-full-play-btn"),
      ttsFullPlayIcon: document.getElementById("tts-full-play-icon"),
      ttsFullSkipNext: document.getElementById("tts-full-skip-next"),
      ttsFullSpeedSelect: document.getElementById("tts-full-speed-select"),
      ttsFullVoiceSelect: document.getElementById("tts-full-voice-select"),
      ttsFullTimerSelect: document.getElementById("tts-full-timer-select"),

      // Voice Catalog Modal & Triggers
      btnOpenVoiceCatalog: document.getElementById("btn-open-voice-catalog"),
      modalVoiceCatalog: document.getElementById("modal-voice-catalog"),
      btnCloseVoiceCatalog: document.getElementById("btn-close-voice-catalog"),
      voiceCatalogGrid: document.getElementById("voice-catalog-grid"),
      voiceStatsInfo: document.getElementById("voice-stats-info"),
      aiVoiceBadgeCount: document.getElementById("ai-voice-badge-count")
    };

    // TTS Engine State (Đồng bộ TtsVoiceCatalog Android)
    const initialSavedVoice = localStorage.getItem("epubpro_selected_ai_voice") || "quang_minh";
    this.tts = {
      synth: window.speechSynthesis,
      utterance: null,
      isPlaying: false,
      speed: 1.0,
      selectedAiVoiceId: initialSavedVoice,
      downloadedVoiceIds: new Set([initialSavedVoice, "quang_minh", "ngoc_ngan"]),
      chunks: [],
      currentChunkIndex: 0,
      sleepTimerId: null,
      sleepTimerMode: "off",
      isFullPlayerOpen: false,
      previewingVoiceId: null
    };

    // Audio Engine Độc Lập cho Giọng Đọc Tiếng Việt (Không phụ thuộc Windows Speech Pack)
    this.audioPlayer = new Audio();
    // Không đặt crossOrigin = "anonymous" để trình duyệt phát trực tiếp audio stream không bị vướng CORS policy
    this.subChunks = [];
    this.currentSubChunkIndex = 0;

    this.parser = new EpubParser();
    window.ReaderApp = this;
    this.init();
  }

  loadSettings() {
    const saved = localStorage.getItem("epubpro_web_settings");
    const defaults = {
      themeMode: "sepia",
      fontFamily: "'Literata', serif",
      fontSizePx: 18,
      lineHeight: 1.75,
      textAlignment: "justify",
      marginPx: 32,
      readingMode: "paged"
    };
    if (saved) {
      try {
        return Object.assign({}, defaults, JSON.parse(saved));
      } catch (e) {
        return defaults;
      }
    }
    return defaults;
  }

  saveSettings() {
    localStorage.setItem("epubpro_web_settings", JSON.stringify(this.settings));
  }

  async init() {
    this.applySettings();
    this.bindEvents();
    this.loadBookmarks();
    this.initTtsVoices(); // Khởi tạo dropdown giọng AI với giá trị đã lưu ngay lập tức

    // Dọn dẹp cache sách demo cũ nếu còn sót lại
    this.cleanupDemoCache();

    // Khởi tạo IndexedDB Sách & Lịch sử
    await this.storage.init();
    this.customBooks = await this.storage.getAllBooks();
    this.readingHistory = await this.storage.getHistory();

    // Khởi tạo kho giọng đọc AI Tiếng Việt tải về (TtsVoiceStorage)
    await this.initAiVoiceCatalog();

    // Khôi phục cuốn sách gần nhất từ lịch sử đọc hoặc sách đã lưu
    if (this.readingHistory.length > 0) {
      const latestHistory = this.readingHistory[0];
      const targetBook = this.customBooks.find(b => b.title === latestHistory.bookTitle);
      if (targetBook) {
        this.loadBook(targetBook, false, latestHistory.chapterIndex, latestHistory.pageIndex);
      } else if (this.customBooks.length > 0) {
        this.loadBook(this.customBooks[0], false);
      } else {
        this.showWelcomeScreen();
      }
    } else if (this.customBooks.length > 0) {
      this.loadBook(this.customBooks[0], false);
    } else {
      this.showWelcomeScreen();
    }

    this.renderLibrary();
  }

  /**
   * Xóa sạch các cache và mốc lịch sử liên quan đến các cuốn sách demo trước đây
   */
  cleanupDemoCache() {
    const demoTitles = ["Dế Mèn Phiêu Lưu Ký", "Số Đỏ", "Tắt Đèn", "Truyện Kiều", "Sherlock Holmes: Vụ Án Thung Lũng Khủng Khiếp"];
    const savedLastBook = localStorage.getItem("epubpro_last_book");
    if (savedLastBook) {
      try {
        const parsed = JSON.parse(savedLastBook);
        if (demoTitles.includes(parsed.title)) {
          localStorage.removeItem("epubpro_last_book");
        }
      } catch (e) {}
    }

    // Dọn dẹp trong history
    let history = this.storage.getHistoryLocal();
    history = history.filter(h => !demoTitles.includes(h.bookTitle));
    localStorage.setItem("epubpro_reading_history", JSON.stringify(history));

    demoTitles.forEach(title => {
      localStorage.removeItem(`epubpro_progress_${title}`);
    });
  }

  showWelcomeScreen() {
    this.book = null;
    this.dom.bookTitleDisplay.textContent = "EpubPro Web Reader";
    this.dom.chapterSubtitleDisplay.textContent = "Chưa có sách nào được mở";
    this.dom.viewportPaged.style.display = "none";
    this.dom.viewportScroll.style.display = "none";
    if (this.dom.welcomeScreen) {
      this.dom.welcomeScreen.style.display = "flex";
    }
    this.dom.pageIndicator.textContent = "0 / 0";
    this.dom.btnPrevChapter.disabled = true;
    this.dom.btnNextChapter.disabled = true;
    this.dom.progressSlider.value = 0;
    this.dom.progressPercent.textContent = "0%";
  }

  /**
   * Render lưới danh mục sách & Lịch sử đọc trong Kệ Sách (Library Modal)
   */
  async renderLibrary() {
    if (!this.dom.booksGrid) return;
    this.dom.booksGrid.innerHTML = "";

    this.readingHistory = await this.storage.getHistory();
    const query = (this.librarySearchQuery || "").trim().toLowerCase();
    const filter = this.currentLibraryFilter;

    let itemsToDisplay = [];

    if (filter === "history") {
      // Hiển thị theo lịch sử đọc gần nhất
      itemsToDisplay = this.readingHistory.map(hist => {
        const bookData = this.customBooks.find(b => b.title === hist.bookTitle) || {
          id: hist.bookId,
          title: hist.bookTitle,
          author: hist.author,
          cover: hist.cover,
          coverGradient: hist.coverGradient,
          chapters: new Array(hist.totalChapters || 1)
        };
        return {
          book: bookData,
          history: hist
        };
      });
    } else {
      // Hiển thị sách từ kệ sách cá nhân
      itemsToDisplay = this.customBooks.map(b => {
        const hist = this.readingHistory.find(h => h.bookTitle === b.title);
        return {
          book: b,
          history: hist || null
        };
      });

      if (filter === "reading") {
        itemsToDisplay = itemsToDisplay.filter(item => item.history && item.history.progressPercentage > 0 && item.history.progressPercentage < 98);
      } else if (filter === "completed") {
        itemsToDisplay = itemsToDisplay.filter(item => item.history && item.history.progressPercentage >= 98);
      }
    }

    // Lọc theo tìm kiếm
    if (query.length > 0) {
      itemsToDisplay = itemsToDisplay.filter(item => {
        const t = (item.book.title || "").toLowerCase();
        const a = (item.book.author || "").toLowerCase();
        return t.includes(query) || a.includes(query);
      });
    }

    if (itemsToDisplay.length === 0) {
      this.dom.booksGrid.innerHTML = `
        <div class="library-empty-state" style="grid-column: 1 / -1;">
          <svg class="library-empty-icon" viewBox="0 0 24 24" fill="currentColor">
            <path d="M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z"/>
          </svg>
          <div style="font-size: 1.15rem; font-weight: 600; margin-bottom: 6px; color: var(--text-main);">
            ${filter === "history" ? "Chưa có lịch sử đọc nào" : "Kệ sách của bạn đang trống"}
          </div>
          <p style="font-size: 0.85rem; margin-bottom: 18px; color: var(--text-muted);">
            ${filter === "history" ? "Khi bạn mở và đọc sách, toàn bộ lịch sử và tiến độ sẽ được lưu tại đây." : "Hãy tải tệp sách .epub từ máy tính để bắt đầu trải nghiệm đọc sách."}
          </p>
          <button class="library-nav-btn" id="btn-empty-upload-trigger">
            <span>+ Chọn Tệp .epub Từ Máy</span>
          </button>
        </div>
      `;
      const btnEmptyUpload = document.getElementById("btn-empty-upload-trigger");
      if (btnEmptyUpload) {
        btnEmptyUpload.addEventListener("click", () => this.dom.fileInput.click());
      }
      return;
    }

    itemsToDisplay.forEach(item => {
      const book = item.book;
      const hist = item.history;
      const percent = hist ? hist.progressPercentage : 0;
      const isReading = hist && percent > 0;
      const isCurrentActive = this.book && this.book.title === book.title;

      const card = document.createElement("div");
      card.className = "book-card";

      const bgStyle = book.coverGradient || "linear-gradient(135deg, #4F46E5 0%, #06B6D4 100%)";

      // Định dạng thời gian đọc gần nhất
      let timeAgoText = "";
      if (hist && hist.lastReadAt) {
        const diffMs = Date.now() - hist.lastReadAt;
        const mins = Math.floor(diffMs / 60000);
        const hours = Math.floor(mins / 60);
        const days = Math.floor(hours / 24);
        if (mins < 2) timeAgoText = "Vừa xong";
        else if (mins < 60) timeAgoText = `${mins} phút trước`;
        else if (hours < 24) timeAgoText = `${hours} giờ trước`;
        else timeAgoText = new Date(hist.lastReadAt).toLocaleDateString("vi-VN");
      }

      card.innerHTML = `
        <div class="book-card-cover" style="background: ${bgStyle};">
          <div class="book-card-badge">${percent >= 98 ? "Đã xong" : (isReading ? "Đang đọc" : "Chưa đọc")}</div>
          <div class="book-card-cover-text">
            <div class="book-card-cover-title">${book.title}</div>
            <div class="book-card-cover-author">${book.author || "Khuyết danh"}</div>
          </div>
        </div>
        <div class="book-card-body">
          <div style="margin-bottom: 6px;">
            ${timeAgoText ? `<span class="history-last-read-tag">⏳ ${timeAgoText}</span>` : ""}
            ${hist && hist.chapterTitle ? `<div style="font-size: 0.8rem; font-weight: 600; color: var(--text-main); margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${hist.chapterTitle}</div>` : ""}
          </div>
          <div class="book-card-meta">
            <span>${book.chapters ? book.chapters.length : 1} chương</span>
            <span>${isReading ? `Đã đọc ${percent}%` : "Chưa đọc"}</span>
          </div>
          <div class="book-card-progress-bar">
            <div class="book-card-progress-fill" style="width: ${percent}%;"></div>
          </div>
          <div class="book-card-footer">
            <button class="read-btn ${isCurrentActive ? "active" : ""}">
              ${isCurrentActive ? "Đang đọc dở" : (isReading ? "Đọc tiếp ›" : "Bắt đầu đọc ›")}
            </button>
            <button class="delete-book-btn" title="Xóa khỏi kệ sách">✕</button>
          </div>
        </div>
      `;

      const readBtn = card.querySelector(".read-btn");
      const handleOpenBook = async () => {
        let fullBook = book;
        if (!fullBook.chapters || fullBook.chapters.length === 0) {
          fullBook = await this.storage.getBook(book.id);
        }
        if (fullBook) {
          const targetChap = hist ? hist.chapterIndex : 0;
          const targetPg = hist ? hist.pageIndex : 0;
          this.loadBook(fullBook, true, targetChap, targetPg);
          this.closeAllDrawers();
        } else {
          this.showToast("Không tìm thấy dữ liệu sách");
        }
      };

      card.addEventListener("click", handleOpenBook);
      if (readBtn) {
        readBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          handleOpenBook();
        });
      }

      const deleteBtn = card.querySelector(".delete-book-btn");
      if (deleteBtn) {
        deleteBtn.addEventListener("click", async (e) => {
          e.stopPropagation();
          if (confirm(`Bạn có chắc muốn xóa cuốn sách "${book.title}" khỏi kệ sách và lịch sử đọc không?`)) {
            await this.storage.deleteBook(book.id, book.title);
            this.customBooks = this.customBooks.filter(b => b.id !== book.id);
            this.readingHistory = this.readingHistory.filter(h => h.bookTitle !== book.title);
            if (this.book && this.book.title === book.title) {
              this.showWelcomeScreen();
            }
            this.renderLibrary();
            this.showToast(`Đã xóa "${book.title}"`);
          }
        });
      }

      this.dom.booksGrid.appendChild(card);
    });
  }

  /**
   * Tải một cuốn sách vào Reader
   */
  loadBook(bookData, saveToStorage = true, targetChapter = null, targetPage = null) {
    if (!bookData || !bookData.chapters || bookData.chapters.length === 0) {
      this.showToast("Dữ liệu sách không hợp lệ");
      return;
    }

    if (this.tts.isPlaying) {
      this.stopTts();
    }

    this.book = bookData;
    if (this.dom.welcomeScreen) {
      this.dom.welcomeScreen.style.display = "none";
    }
    this.dom.bookTitleDisplay.textContent = this.book.title;

    // Tìm lịch sử đọc dở gần nhất nếu không truyền param cụ thể
    if (targetChapter === null || targetPage === null) {
      const hist = this.readingHistory.find(h => h.bookTitle === this.book.title);
      if (hist) {
        targetChapter = hist.chapterIndex || 0;
        targetPage = hist.pageIndex || 0;
      } else {
        targetChapter = 0;
        targetPage = 0;
      }
    }

    if (targetChapter >= this.book.chapters.length) targetChapter = 0;

    this.currentChapterIndex = targetChapter;
    this.currentPageIndex = targetPage;

    // Render TOC & Chapter
    this.renderToc();
    this.renderChapter(this.currentChapterIndex, this.currentPageIndex);

    if (saveToStorage) {
      this.storage.saveBook(this.book);
      localStorage.setItem("epubpro_last_book_id", this.book.id);
    }

    this.showToast(`Đã mở: ${this.book.title}`);
  }

  renderToc() {
    this.dom.tocList.innerHTML = "";
    if (!this.book || !this.book.chapters) return;

    this.book.chapters.forEach((chap, idx) => {
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.className = `toc-item-btn ${idx === this.currentChapterIndex ? "active" : ""}`;
      btn.innerHTML = `
        <span>${chap.title}</span>
        <span style="font-size: 0.75rem; opacity: 0.6;">Ch.${idx + 1}</span>
      `;
      btn.addEventListener("click", () => {
        this.goToChapter(idx);
        this.closeAllDrawers();
      });
      li.appendChild(btn);
      this.dom.tocList.appendChild(li);
    });
  }

  renderChapter(chapterIdx, targetPage = 0) {
    if (!this.book || !this.book.chapters[chapterIdx]) return;

    this.currentChapterIndex = chapterIdx;
    const chapter = this.book.chapters[chapterIdx];

    this.dom.chapterSubtitleDisplay.textContent = chapter.title;

    // Cập nhật nội dung vào DOM
    if (this.settings.readingMode === "paged") {
      this.dom.viewportPaged.style.display = "flex";
      this.dom.viewportScroll.style.display = "none";
      this.dom.pagedContainer.innerHTML = chapter.content;
      this.updatePagedMetrics(targetPage);
    } else {
      this.dom.viewportPaged.style.display = "none";
      this.dom.viewportScroll.style.display = "block";
      this.dom.scrollInner.innerHTML = chapter.content;
      this.dom.viewportScroll.scrollTop = 0;
      this.dom.pageIndicator.textContent = `${chapter.title}`;
    }

    // Cập nhật trạng thái nút Next/Prev Chapter
    this.dom.btnPrevChapter.disabled = this.currentChapterIndex <= 0;
    this.dom.btnNextChapter.disabled = this.currentChapterIndex >= this.book.chapters.length - 1;

    // Highlight active chapter trong TOC
    const tocButtons = this.dom.tocList.querySelectorAll(".toc-item-btn");
    tocButtons.forEach((btn, idx) => {
      btn.classList.toggle("active", idx === this.currentChapterIndex);
    });

    this.updateProgressSlider();
    this.saveProgress();

    // Chuẩn bị lại danh sách đoạn văn TTS cho chapter mới
    this.prepareTtsChunks();
  }

  /**
   * Tính toán số trang và cột trong chế độ phân trang ngang
   */
  updatePagedMetrics(targetPage = 0) {
    if (this.settings.readingMode !== "paged") return;

    requestAnimationFrame(() => {
      const container = this.dom.pagedContainer;
      const viewportWidth = window.innerWidth;
      const margin = this.settings.marginPx;
      const colWidth = viewportWidth - (2 * margin);
      const colGap = 2 * margin;

      container.style.columnWidth = `${colWidth}px`;
      container.style.columnGap = `${colGap}px`;
      container.style.paddingLeft = `${margin}px`;
      container.style.paddingRight = `${margin}px`;

      const scrollW = container.scrollWidth;
      const step = colWidth + colGap;
      const totalPages = Math.max(1, Math.round(scrollW / step) || Math.ceil(scrollW / viewportWidth));

      this.totalPagesInChapter = totalPages;
      if (targetPage >= totalPages) targetPage = totalPages - 1;
      if (targetPage < 0) targetPage = 0;

      this.goToPage(targetPage, false);
    });
  }

  goToPage(pageIdx, smooth = true) {
    if (this.settings.readingMode !== "paged") return;

    if (pageIdx < 0) {
      if (this.currentChapterIndex > 0) {
        this.goToChapter(this.currentChapterIndex - 1, 9999);
      }
      return;
    }

    if (pageIdx >= this.totalPagesInChapter) {
      if (this.currentChapterIndex < this.book.chapters.length - 1) {
        this.goToChapter(this.currentChapterIndex + 1, 0);
      }
      return;
    }

    this.currentPageIndex = pageIdx;
    const viewportWidth = window.innerWidth;
    const offset = -this.currentPageIndex * viewportWidth;
    this.dom.pagedContainer.style.transform = `translateX(${offset}px)`;

    this.dom.pageIndicator.textContent = `Trang ${this.currentPageIndex + 1} / ${this.totalPagesInChapter}`;
    this.updateProgressSlider();
    this.saveProgress();
  }

  nextPage() {
    if (!this.book) return;
    if (this.settings.readingMode === "paged") {
      this.goToPage(this.currentPageIndex + 1);
    } else {
      this.dom.viewportScroll.scrollBy({ top: window.innerHeight * 0.75, behavior: "smooth" });
    }
  }

  prevPage() {
    if (!this.book) return;
    if (this.settings.readingMode === "paged") {
      this.goToPage(this.currentPageIndex - 1);
    } else {
      this.dom.viewportScroll.scrollBy({ top: -window.innerHeight * 0.75, behavior: "smooth" });
    }
  }

  goToChapter(chapIdx, targetPage = 0) {
    if (!this.book || chapIdx < 0 || chapIdx >= this.book.chapters.length) return;
    this.renderChapter(chapIdx, targetPage);
  }

  nextChapter() {
    if (!this.book) return;
    if (this.currentChapterIndex < this.book.chapters.length - 1) {
      this.goToChapter(this.currentChapterIndex + 1, 0);
    }
  }

  prevChapter() {
    if (!this.book) return;
    if (this.currentChapterIndex > 0) {
      this.goToChapter(this.currentChapterIndex - 1, 0);
    }
  }

  updateProgressSlider() {
    if (!this.book || !this.book.chapters || this.book.chapters.length === 0) return;
    const totalChapters = this.book.chapters.length;
    let fraction = this.currentChapterIndex / totalChapters;
    if (this.settings.readingMode === "paged" && this.totalPagesInChapter > 1) {
      fraction += (this.currentPageIndex / this.totalPagesInChapter) * (1 / totalChapters);
    }
    const percent = Math.min(100, Math.round(fraction * 100));
    this.dom.progressSlider.value = percent;
    this.dom.progressPercent.textContent = `${percent}%`;
  }

  /**
   * Lưu lại tiến độ và Lịch sử đọc (Chuẩn ReadingHistoryRecord Android)
   */
  saveProgress() {
    if (!this.book || !this.book.chapters) return;

    const totalChapters = this.book.chapters.length || 1;
    let fraction = this.currentChapterIndex / totalChapters;
    if (this.settings.readingMode === "paged" && this.totalPagesInChapter > 1) {
      fraction += (this.currentPageIndex / this.totalPagesInChapter) * (1 / totalChapters);
    }
    const percent = Math.min(100, Math.round(fraction * 100));
    const curChap = this.book.chapters[this.currentChapterIndex];

    const historyRecord = {
      bookId: this.book.id || ("book-" + this.book.title),
      bookTitle: this.book.title,
      author: this.book.author || "Khuyết danh",
      cover: this.book.cover || null,
      coverGradient: this.book.coverGradient || "linear-gradient(135deg, #4F46E5 0%, #06B6D4 100%)",
      chapterIndex: this.currentChapterIndex,
      chapterTitle: curChap ? curChap.title : `Chương ${this.currentChapterIndex + 1}`,
      pageIndex: this.currentPageIndex,
      totalPagesInChapter: this.totalPagesInChapter,
      progressPercentage: percent,
      totalChapters: totalChapters,
      lastReadAt: Date.now(),
      updatedAt: Date.now()
    };

    this.storage.saveHistory(historyRecord);
  }

  toggleControls() {
    this.showControls = !this.showControls;
    this.dom.topbar.classList.toggle("hidden", !this.showControls);
    this.dom.bottombar.classList.toggle("hidden", !this.showControls);
  }

  /**
   * Áp dụng Theme, Font, Cỡ chữ, Giãn dòng, Căn lề vào DOM
   */
  applySettings() {
    document.documentElement.setAttribute("data-theme", this.settings.themeMode);

    const root = document.documentElement;
    root.style.setProperty("--book-font", this.settings.fontFamily);
    root.style.setProperty("--book-font-size", `${this.settings.fontSizePx}px`);
    root.style.setProperty("--book-line-height", `${this.settings.lineHeight}`);
    root.style.setProperty("--book-text-align", this.settings.textAlignment);

    const styleEl = document.getElementById("dynamic-reader-styles") || document.createElement("style");
    styleEl.id = "dynamic-reader-styles";
    styleEl.textContent = `
      .chapter-content, .paged-column-container, .scroll-inner-wrapper {
        font-family: ${this.settings.fontFamily} !important;
        font-size: ${this.settings.fontSizePx}px !important;
        line-height: ${this.settings.lineHeight} !important;
        text-align: ${this.settings.textAlignment} !important;
      }
    `;
    if (!document.getElementById("dynamic-reader-styles")) {
      document.head.appendChild(styleEl);
    }

    this.updateSettingsUI();

    if (this.book) {
      this.updatePagedMetrics(this.currentPageIndex);
    }
  }

  updateSettingsUI() {
    document.querySelectorAll(".theme-pill-btn").forEach(btn => {
      btn.classList.toggle("active", btn.getAttribute("data-theme-val") === this.settings.themeMode);
    });

    document.querySelectorAll(".chip-font-btn").forEach(btn => {
      btn.classList.toggle("active", btn.getAttribute("data-font-val") === this.settings.fontFamily);
    });

    const fsDisplay = document.getElementById("val-font-size");
    const fsSlider = document.getElementById("slider-font-size");
    if (fsDisplay) fsDisplay.textContent = `${this.settings.fontSizePx}px`;
    if (fsSlider) fsSlider.value = this.settings.fontSizePx;

    const lhDisplay = document.getElementById("val-line-height");
    const lhSlider = document.getElementById("slider-line-height");
    if (lhDisplay) lhDisplay.textContent = `${this.settings.lineHeight}`;
    if (lhSlider) lhSlider.value = this.settings.lineHeight;

    const mgDisplay = document.getElementById("val-margin");
    const mgSlider = document.getElementById("slider-margin");
    if (mgDisplay) mgDisplay.textContent = `${this.settings.marginPx}px`;
    if (mgSlider) mgSlider.value = this.settings.marginPx;

    document.querySelectorAll(".chip-align-btn").forEach(btn => {
      btn.classList.toggle("active", btn.getAttribute("data-align-val") === this.settings.textAlignment);
    });

    document.querySelectorAll(".seg-mode-btn").forEach(btn => {
      btn.classList.toggle("active", btn.getAttribute("data-mode-val") === this.settings.readingMode);
    });
  }

  /**
   * Bookmark Management
   */
  loadBookmarks() {
    const saved = localStorage.getItem("epubpro_bookmarks");
    if (saved) {
      try {
        this.bookmarks = JSON.parse(saved);
      } catch (e) {
        this.bookmarks = [];
      }
    }
    this.renderBookmarks();
  }

  saveBookmarks() {
    localStorage.setItem("epubpro_bookmarks", JSON.stringify(this.bookmarks));
    this.renderBookmarks();
  }

  addBookmark() {
    if (!this.book) return;
    const chap = this.book.chapters[this.currentChapterIndex];
    const newBm = {
      id: Date.now(),
      bookTitle: this.book.title,
      chapterIndex: this.currentChapterIndex,
      chapterTitle: chap ? chap.title : `Chương ${this.currentChapterIndex + 1}`,
      pageIndex: this.currentPageIndex,
      dateString: new Date().toLocaleDateString("vi-VN", { hour: "2-digit", minute: "2-digit" })
    };
    this.bookmarks.unshift(newBm);
    this.saveBookmarks();
    this.showToast("Đã lưu dấu trang vị trí hiện tại");
  }

  renderBookmarks() {
    this.dom.bookmarksList.innerHTML = "";
    if (this.bookmarks.length === 0) {
      this.dom.bookmarksList.innerHTML = `
        <div style="text-align: center; padding: 32px 16px; color: var(--text-muted);">
          <p>Chưa có dấu trang nào được lưu.</p>
        </div>
      `;
      return;
    }

    this.bookmarks.forEach(bm => {
      const div = document.createElement("div");
      div.className = "toc-item-btn";
      div.style.marginBottom = "8px";
      div.innerHTML = `
        <div style="flex: 1;">
          <div style="font-weight: 600; color: var(--text-main); font-size: 0.9rem;">${bm.chapterTitle}</div>
          <div style="font-size: 0.75rem; color: var(--text-muted);">${bm.bookTitle} • Trang ${bm.pageIndex + 1} • ${bm.dateString}</div>
        </div>
        <button class="icon-btn delete-bm-btn" title="Xóa dấu trang" style="width: 32px; height: 32px;">✕</button>
      `;
      div.querySelector(".delete-bm-btn").addEventListener("click", (e) => {
        e.stopPropagation();
        this.bookmarks = this.bookmarks.filter(b => b.id !== bm.id);
        this.saveBookmarks();
      });
      div.addEventListener("click", async () => {
        if (this.book && this.book.title === bm.bookTitle) {
          this.goToChapter(bm.chapterIndex, bm.pageIndex);
        } else {
          const targetBook = this.customBooks.find(b => b.title === bm.bookTitle);
          if (targetBook) {
            this.loadBook(targetBook, true, bm.chapterIndex, bm.pageIndex);
          } else {
            this.showToast("Dấu trang thuộc cuốn sách chưa được tải vào kệ");
          }
        }
        this.closeAllDrawers();
      });
      this.dom.bookmarksList.appendChild(div);
    });
  }

  // =========================================================================
  // TTS AUDIO ENGINE (THIẾT KẾ ĐỒNG BỘ CHUẨN APP EPUBPRO)
  // =========================================================================

  /**
   * Khởi tạo kết nối lưu trữ kho giọng đọc AI và đồng bộ trạng thái
   */
  async initAiVoiceCatalog() {
    if (window.TtsVoiceStorage) {
      await window.TtsVoiceStorage.init();
      this.tts.downloadedVoiceIds = await window.TtsVoiceStorage.getDownloadedVoiceIds();
      if (this.tts.selectedAiVoiceId) {
        this.tts.downloadedVoiceIds.add(this.tts.selectedAiVoiceId);
      }
      this.updateVoiceBadgeCount();
      this.initTtsVoices();
      this.updateTtsUi();
    }
  }

  /**
   * Cập nhật số lượng giọng đọc AI đã tải về máy trên huy hiệu và thanh thống kê
   */
  updateVoiceBadgeCount() {
    if (this.dom.aiVoiceBadgeCount) {
      this.dom.aiVoiceBadgeCount.textContent = this.tts.downloadedVoiceIds.size;
    }
    if (this.dom.voiceStatsInfo) {
      const count = this.tts.downloadedVoiceIds.size;
      const totalMb = (count * 60.6).toFixed(1);
      this.dom.voiceStatsInfo.innerHTML = `
        <span>💾 Đã tải về: <strong>${count} / 25</strong> mô hình</span>
        <span>•</span>
        <span>Dung lượng đã lưu trữ: <strong>${totalMb} MB</strong></span>
      `;
    }
  }

  /**
   * Nạp danh mục giọng đọc AI Tiếng Việt vào dropdown trình phát
   * Loại bỏ hoàn toàn các giọng tiếng Anh mặc định của hệ điều hành
   */
  initTtsVoices() {
    const select = this.dom.ttsFullVoiceSelect;
    if (!select) return;

    select.innerHTML = "";
    const catalog = window.TtsVoiceCatalog || [];
    const downloadedIds = this.tts.downloadedVoiceIds || new Set(["quang_minh", "ngoc_ngan"]);
    if (this.tts.selectedAiVoiceId) {
      downloadedIds.add(this.tts.selectedAiVoiceId);
    }

    // Nhóm 1: Giọng đã tải về máy (Sẵn sàng Offline)
    const downloadedVoices = catalog.filter(v => downloadedIds.has(v.id));
    if (downloadedVoices.length > 0) {
      const groupDownloaded = document.createElement("optgroup");
      groupDownloaded.label = "⭐ Giọng AI Đã Tải Về (Sẵn sàng Offline)";
      downloadedVoices.forEach(v => {
        const opt = document.createElement("option");
        opt.value = v.id;
        opt.textContent = `🎙️ ${v.displayName} (${v.region} - ${v.gender})`;
        if (v.id === this.tts.selectedAiVoiceId) opt.selected = true;
        groupDownloaded.appendChild(opt);
      });
      select.appendChild(groupDownloaded);
    }

    // Nhóm 2: Các giọng AI khác trong danh mục (Tải từ HuggingFace)
    const otherVoices = catalog.filter(v => !downloadedIds.has(v.id));
    if (otherVoices.length > 0) {
      const groupOther = document.createElement("optgroup");
      groupOther.label = "☁️ Giọng AI Từ HuggingFace (Cần tải về)";
      otherVoices.forEach(v => {
        const opt = document.createElement("option");
        opt.value = v.id;
        opt.textContent = `📥 ${v.displayName} (${v.region} - ${v.downloadSize})`;
        if (v.id === this.tts.selectedAiVoiceId) opt.selected = true;
        groupOther.appendChild(opt);
      });
      select.appendChild(groupOther);
    }

    // Tùy chọn mở nhanh kho giọng
    const optCatalog = document.createElement("option");
    optCatalog.value = "__open_catalog__";
    optCatalog.textContent = "⚙️ Quản lý & Tải thêm giọng AI...";
    select.appendChild(optCatalog);

    // Đảm bảo có giọng được chọn hợp lệ
    if (!catalog.some(v => v.id === this.tts.selectedAiVoiceId)) {
      this.tts.selectedAiVoiceId = "quang_minh";
    }
    select.value = this.tts.selectedAiVoiceId;
  }

  /**
   * Mở modal Kho Giọng Đọc AI Tiếng Việt tải về
   */
  openVoiceCatalogModal() {
    this.closeAllDrawers();
    if (this.dom.modalVoiceCatalog) {
      this.dom.modalVoiceCatalog.classList.add("active");
      if (this.dom.backdrop) this.dom.backdrop.classList.add("active");
      this.updateVoiceBadgeCount();
      this.renderVoiceCatalogCards("all");
    }
  }

  /**
   * Đóng modal Kho Giọng Đọc AI Tiếng Việt
   */
  closeVoiceCatalogModal() {
    this.closeAllDrawers();
  }

  /**
   * Render danh sách các Card giọng đọc AI theo bộ lọc
   *
   * @param {string} filter Tiêu chí lọc (all, recommended, downloaded, Bac, Nam, male, female)
   */
  renderVoiceCatalogCards(filter = "all") {
    const grid = this.dom.voiceCatalogGrid;
    if (!grid) return;

    grid.innerHTML = "";
    const catalog = window.TtsVoiceCatalog || [];
    const downloadedIds = this.tts.downloadedVoiceIds || new Set();

    let filtered = catalog;
    if (filter === "recommended") {
      filtered = catalog.filter(v => v.recommended);
    } else if (filter === "downloaded") {
      filtered = catalog.filter(v => downloadedIds.has(v.id));
    } else if (filter === "Bac") {
      filtered = catalog.filter(v => v.region.includes("Bắc"));
    } else if (filter === "Nam") {
      filtered = catalog.filter(v => v.region.includes("Nam") || v.region.includes("Hải Ngoại"));
    } else if (filter === "male") {
      filtered = catalog.filter(v => v.gender === "Nam");
    } else if (filter === "female") {
      filtered = catalog.filter(v => v.gender === "Nữ");
    }

    if (filtered.length === 0) {
      grid.innerHTML = `
        <div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: var(--text-muted);">
          Không tìm thấy giọng đọc nào phù hợp với bộ lọc hiện tại.
        </div>
      `;
      return;
    }

    filtered.forEach(voice => {
      const isSelected = this.tts.selectedAiVoiceId === voice.id;
      // Nếu là giọng đang chọn hoặc có trong danh sách đã tải -> coi là đã sẵn sàng
      const isDownloaded = isSelected || downloadedIds.has(voice.id);
      const isPreviewing = this.tts.previewingVoiceId === voice.id;

      const card = document.createElement("div");
      card.className = `voice-card ${isSelected ? "active-selected" : ""}`;
      card.id = `voice-card-${voice.id}`;

      const avatarClass = voice.gender === "Nữ" ? "female" : "male";
      const initialLetter = voice.displayName.charAt(0);

      card.innerHTML = `
        <div class="voice-card-header">
          <div class="voice-avatar ${avatarClass}">
            ${initialLetter}
          </div>
          <div class="voice-header-text">
            <div class="voice-name">
              <span>${voice.displayName}</span>
              ${voice.recommended ? '<span class="voice-star-badge" title="Giọng đọc khuyên dùng">⭐</span>' : ''}
              ${isSelected ? '<span style="font-size: 0.7rem; color: #D97757; font-weight: 700; margin-left: auto;">ĐANG CHỌN</span>' : ''}
            </div>
            <div class="voice-meta-tags">
              <span class="voice-tag ${voice.recommended ? 'highlight' : ''}">${voice.region}</span>
              <span class="voice-tag">${voice.gender}</span>
              <span class="voice-tag">${voice.downloadSize}</span>
            </div>
          </div>
        </div>

        <div class="voice-desc">
          ${voice.styleDesc}
        </div>

        <!-- Thanh tiến trình tải download -->
        <div class="voice-progress-container" id="progress-box-${voice.id}">
          <div class="voice-progress-labels">
            <span id="progress-text-${voice.id}">Đang tải về máy...</span>
            <span id="progress-percent-${voice.id}">0%</span>
          </div>
          <div class="voice-progress-track">
            <div class="voice-progress-bar" id="progress-bar-${voice.id}"></div>
          </div>
        </div>

        <div class="voice-card-actions">
          <button class="voice-btn-preview" data-voice-id="${voice.id}" title="Nghe thử giọng mẫu">
            ${isPreviewing ? '⏸️ Dừng' : '🔊 Nghe thử'}
          </button>

          ${isDownloaded ? `
            <button class="voice-btn-download downloaded" data-voice-id="${voice.id}">
              ✓ ${isSelected ? "Đang dùng" : "Chọn đọc"}
            </button>
            <button class="voice-btn-delete" data-voice-id="${voice.id}" title="Xóa mô hình đã tải để giải phóng bộ nhớ">
              🗑️
            </button>
          ` : `
            <button class="voice-btn-download" data-voice-id="${voice.id}">
              📥 Tải về (${voice.downloadSize})
            </button>
          `}
        </div>
      `;

      // Event listener cho nút Nghe thử
      const btnPreview = card.querySelector(".voice-btn-preview");
      btnPreview.addEventListener("click", () => {
        if (this.tts.previewingVoiceId === voice.id) {
          if (window.TtsVoiceStorage) window.TtsVoiceStorage.stopPreview();
          if (window.speechSynthesis) window.speechSynthesis.cancel();
          this.tts.previewingVoiceId = null;
          this.renderVoiceCatalogCards(filter);
        } else {
          this.tts.previewingVoiceId = voice.id;
          this.renderVoiceCatalogCards(filter);
          window.TtsVoiceStorage.previewVoice(
            voice.id,
            () => {},
            () => {
              this.tts.previewingVoiceId = null;
              this.renderVoiceCatalogCards(filter);
            }
          );
        }
      });

      // Event listener cho nút Tải về / Chọn đọc
      const btnDownload = card.querySelector(".voice-btn-download");
      btnDownload.addEventListener("click", async () => {
        if (isDownloaded) {
          // Đặt làm giọng đọc mặc định
          this.tts.selectedAiVoiceId = voice.id;
          this.tts.downloadedVoiceIds.add(voice.id);
          localStorage.setItem("epubpro_selected_ai_voice", voice.id);
          this.initTtsVoices();
          this.updateTtsUi();
          this.renderVoiceCatalogCards(filter);
          this.showToast(`Đã chọn giọng AI: ${voice.displayName}`);
          if (this.audioPlayer) {
            this.audioPlayer.pause();
            this.audioPlayer.src = "";
          }
          if (this.tts.isPlaying) {
            this.playTtsChunk(this.tts.currentChunkIndex);
          }
        } else {
          // Bắt đầu tải mô hình từ HuggingFace về máy
          btnDownload.disabled = true;
          btnDownload.textContent = "Đang kết nối...";
          const progressBox = card.querySelector(`#progress-box-${voice.id}`);
          const progressBar = card.querySelector(`#progress-bar-${voice.id}`);
          const progressPercent = card.querySelector(`#progress-percent-${voice.id}`);
          if (progressBox) progressBox.classList.add("active");

          const success = await window.TtsVoiceStorage.downloadModel(voice.id, (p) => {
            const pct = Math.round(p * 100);
            if (progressBar) progressBar.style.width = `${pct}%`;
            if (progressPercent) progressPercent.textContent = `${pct}%`;
          });

          if (success) {
            this.tts.downloadedVoiceIds.add(voice.id);
            this.tts.selectedAiVoiceId = voice.id;
            localStorage.setItem("epubpro_selected_ai_voice", voice.id);
            this.updateVoiceBadgeCount();
            this.initTtsVoices();
            this.renderVoiceCatalogCards(filter);
            this.showToast(`Đã tải thành công giọng AI ${voice.displayName}`);
            if (this.audioPlayer) {
              this.audioPlayer.pause();
              this.audioPlayer.src = "";
            }
            if (this.tts.isPlaying) {
              this.playTtsChunk(this.tts.currentChunkIndex);
            }
          } else {
            this.showToast(`Không thể tải mô hình giọng đọc ${voice.displayName}. Vui lòng thử lại.`);
            if (progressBox) progressBox.classList.remove("active");
            btnDownload.disabled = false;
            btnDownload.textContent = `📥 Tải về (${voice.downloadSize})`;
          }
        }
      });

      // Event listener cho nút Xóa mô hình
      const btnDelete = card.querySelector(".voice-btn-delete");
      if (btnDelete) {
        btnDelete.addEventListener("click", async () => {
          if (confirm(`Bạn có chắc muốn xóa mô hình giọng ${voice.displayName} để giải phóng bộ nhớ?`)) {
            await window.TtsVoiceStorage.deleteModel(voice.id);
            this.tts.downloadedVoiceIds.delete(voice.id);
            if (this.tts.selectedAiVoiceId === voice.id) {
              this.tts.selectedAiVoiceId = "ngoc_ngan";
              localStorage.setItem("epubpro_selected_ai_voice", "ngoc_ngan");
            }
            this.updateVoiceBadgeCount();
            this.initTtsVoices();
            this.renderVoiceCatalogCards(filter);
            this.showToast(`Đã xóa mô hình ${voice.displayName}`);
          }
        });
      }

      grid.appendChild(card);
    });
  }

  prepareTtsChunks() {
    const container = this.settings.readingMode === "paged" ? this.dom.pagedContainer : this.dom.scrollInner;
    if (!container) return [];

    const elements = container.querySelectorAll("p, h1, h2, h3, h4, h5, h6, li, blockquote");
    const chunks = [];
    let idx = 0;

    elements.forEach(el => {
      const text = el.textContent.replace(/\s+/g, " ").trim();
      if (text.length > 0) {
        chunks.push({
          index: idx++,
          text: text,
          element: el
        });
      }
    });

    this.tts.chunks = chunks;
    return chunks;
  }

  /**
   * Tách một đoạn văn bản thành các câu nhỏ hơn để truyền qua luồng phát âm thanh liên tục
   *
   * @param {string} text Đoạn văn bản đầy đủ
   * @param {number} maxChars Độ dài tối đa cho mỗi câu
   * @return {Array<string>} Danh sách các câu ngắn
   */
  splitTextIntoSentences(text, maxChars = 160) {
    if (!text) return [];
    const clean = text.replace(/\s+/g, " ").trim();
    if (clean.length <= maxChars) return [clean];

    // Tách theo các dấu câu tiếng Việt: . ! ? ; hoặc xuống dòng
    const regex = /([^.!?;\n]+[.!?;\n]+)/g;
    const parts = clean.match(regex);
    if (!parts || parts.length === 0) {
      const words = clean.split(" ");
      const res = [];
      let cur = "";
      words.forEach(w => {
        if ((cur + " " + w).length > maxChars) {
          if (cur) res.push(cur.trim());
          cur = w;
        } else {
          cur += (cur ? " " : "") + w;
        }
      });
      if (cur) res.push(cur.trim());
      return res;
    }

    const result = [];
    let buffer = "";
    parts.forEach(part => {
      const p = part.trim();
      if ((buffer + " " + p).length > maxChars) {
        if (buffer) result.push(buffer.trim());
        buffer = p;
      } else {
        buffer += (buffer ? " " : "") + p;
      }
    });
    if (buffer) result.push(buffer.trim());
    return result.length > 0 ? result : [clean];
  }

  /**
   * Bắt đầu phát âm thanh TTS cho chương sách hiện tại
   *
   * @param {number} chunkIndex Chỉ số đoạn văn bắt đầu đọc
   * @param {boolean} openFullPlayer Có mở giao diện toàn màn hình không
   */
  startTts(chunkIndex = 0, openFullPlayer = false) {
    if (!this.book) {
      this.showToast("Vui lòng mở một cuốn sách trước khi phát audio");
      return;
    }

    if (this.tts.chunks.length === 0) {
      this.prepareTtsChunks();
    }

    if (this.tts.chunks.length === 0) {
      this.showToast("Không tìm thấy đoạn văn nào để đọc trong chương này");
      return;
    }

    if (openFullPlayer) {
      this.openFullTtsPlayer();
    } else {
      this.dom.ttsMiniBar.classList.add("active");
    }

    this.playTtsChunk(chunkIndex);
  }

  /**
   * Phát âm thanh cho một đoạn văn (TtsChunk)
   * Sử dụng Audio Engine trực tiếp đảm bảo phát âm tiếng Việt chuẩn 100%
   *
   * @param {number} chunkIndex Chỉ số đoạn văn
   */
  playTtsChunk(chunkIndex) {
    if (this.tts.chunks.length === 0) {
      this.prepareTtsChunks();
    }

    if (chunkIndex >= this.tts.chunks.length) {
      if (this.tts.sleepTimerMode === "chapter") {
        this.stopTts();
        this.showToast("Đã đọc xong chương. Hẹn giờ tắt kết thúc.");
        return;
      }
      if (this.currentChapterIndex < this.book.chapters.length - 1) {
        this.nextChapter();
        setTimeout(() => {
          this.prepareTtsChunks();
          this.playTtsChunk(0);
        }, 500);
      } else {
        this.stopTts();
        this.showToast("Đã đọc xong toàn bộ tác phẩm");
      }
      return;
    }

    if (chunkIndex < 0) chunkIndex = 0;
    this.tts.currentChunkIndex = chunkIndex;
    const chunk = this.tts.chunks[chunkIndex];
    if (!chunk) return;

    // Đồng bộ Highlight trên giao diện đọc
    document.querySelectorAll(".tts-active-paragraph").forEach(el => el.classList.remove("tts-active-paragraph"));
    chunk.element.classList.add("tts-active-paragraph");

    // Tự động lật trang hoặc cuộn đến đoạn văn đang đọc
    if (this.settings.readingMode === "paged") {
      const viewportWidth = window.innerWidth || 1;
      const rect = chunk.element.getBoundingClientRect();
      const targetX = rect.left + (window.scrollX || 0);
      const targetPage = Math.floor(targetX / viewportWidth);
      if (targetPage !== this.currentPageIndex && targetPage >= 0 && targetPage < this.totalPagesInChapter) {
        this.goToPage(targetPage, true);
      }
    } else {
      chunk.element.scrollIntoView({ behavior: "smooth", block: "center" });
    }

    // Dừng các luồng âm thanh cũ
    if (this.audioPlayer) {
      try {
        this.audioPlayer.pause();
      } catch (e) {}
    }
    if (this.tts.synth) {
      try {
        this.tts.synth.cancel();
      } catch (e) {}
    }

    // Tách đoạn văn thành các câu ngắn để stream âm thanh mượt mà
    this.subChunks = this.splitTextIntoSentences(chunk.text, 160);
    this.currentSubChunkIndex = 0;

    // Phát câu đầu tiên
    this.playCurrentSubChunk(chunkIndex);
  }

  /**
   * Phát câu âm thanh nhỏ trong đoạn văn hiện tại
   *
   * @param {number} chunkIndex Chỉ số đoạn văn cha
   */
  playCurrentSubChunk(chunkIndex) {
    const chunk = this.tts.chunks[chunkIndex];
    if (!chunk) return;

    // Nếu đã phát hết toàn bộ các câu trong đoạn này -> chuyển sang đoạn tiếp theo
    if (this.currentSubChunkIndex >= this.subChunks.length) {
      this.playTtsChunk(chunkIndex + 1);
      return;
    }

    const subText = this.subChunks[this.currentSubChunkIndex];
    if (!subText) {
      this.currentSubChunkIndex++;
      this.playCurrentSubChunk(chunkIndex);
      return;
    }

    const catalog = window.TtsVoiceCatalog || [];
    const currentModel = catalog.find(v => v.id === this.tts.selectedAiVoiceId) || catalog[0] || { displayName: "Ngọc Ngạn", gender: "Nam" };

    // Tạo URL âm thanh tiếng Việt gọi trực tiếp đến local server /api/tts
    const audioUrl = window.TtsVoiceStorage
      ? window.TtsVoiceStorage.getTtsAudioUrl(subText, currentModel.id, this.tts.speed)
      : `http://127.0.0.1:8080/api/tts?text=${encodeURIComponent(subText)}&voice=${encodeURIComponent(currentModel.id)}&speed=${this.tts.speed}`;

    console.log(`[TTS Engine] Đang phát câu bằng giọng AI: ${currentModel.displayName} (${currentModel.id}) qua ${audioUrl}`);
    this.audioPlayer.src = audioUrl;
    this.audioPlayer.playbackRate = 1.0;

    this.audioPlayer.onplay = () => {
      this.tts.isPlaying = true;
      this.updateTtsUi(chunk);
    };

    this.audioPlayer.onended = () => {
      if (this.tts.isPlaying) {
        this.currentSubChunkIndex++;
        this.playCurrentSubChunk(chunkIndex);
      }
    };

    this.audioPlayer.onerror = (e) => {
      console.warn(`[TTS Engine] Lỗi phát âm thanh cho giọng ${currentModel.displayName}:`, e);
      // Thông báo rõ ràng cho người dùng nếu server chưa kịp phản hồi
      if (this.audioPlayer.src.includes("127.0.0.1:8080") || this.audioPlayer.src.includes("/api/tts")) {
        this.showToast(`⚠️ Đang chuẩn bị mô hình ${currentModel.displayName}... Vui lòng đợi trong giây lát.`);
        setTimeout(() => {
          if (this.tts.isPlaying) {
            this.audioPlayer.src = audioUrl;
            this.audioPlayer.play().catch(() => {
              this.showToast(`⚠️ Không thể kết nối server AI. Vui lòng đảm bảo 'python server.py' đang chạy.`);
            });
          }
        }, 1500);
      }
    };

    const playPromise = this.audioPlayer.play();
    if (playPromise !== undefined) {
      playPromise.catch(err => {
        console.warn("[TTS Engine] Autoplay policy chặn:", err);
      });
    }

    this.updateTtsUi(chunk);
  }

  /**
   * Phương thức dự phòng sử dụng Web Speech API khi mạng ngoại tuyến
   *
   * @param {string} text Nội dung cần đọc
   * @param {number} chunkIndex Chỉ số đoạn văn
   */
  fallbackPlayWebSpeech(text, chunkIndex) {
    if (!this.tts.synth) return;

    this.tts.synth.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = this.tts.speed;

    const catalog = window.TtsVoiceCatalog || [];
    const currentModel = catalog.find(v => v.id === this.tts.selectedAiVoiceId) || catalog[0];

    utterance.pitch = currentModel.gender === "Nữ" ? 1.15 : 0.95;

    const allVoices = this.tts.synth.getVoices() || [];
    const viVoice = allVoices.find(v => v.lang.includes("vi") || v.lang.includes("VIE") || (v.name && v.name.toLowerCase().includes("vietnam")));
    if (viVoice) {
      utterance.voice = viVoice;
      utterance.lang = viVoice.lang;
    } else {
      utterance.lang = "vi-VN";
    }

    utterance.onstart = () => {
      this.tts.isPlaying = true;
      this.updateTtsUi(this.tts.chunks[chunkIndex]);
    };

    utterance.onend = () => {
      if (this.tts.isPlaying) {
        this.currentSubChunkIndex++;
        this.playCurrentSubChunk(chunkIndex);
      }
    };

    this.tts.utterance = utterance;
    this.tts.synth.speak(utterance);
  }

  updateTtsUi(chunk = null) {
    const curChunk = chunk || this.tts.chunks[this.tts.currentChunkIndex] || { text: "" };
    const curIdx = this.tts.currentChunkIndex;
    const total = this.tts.chunks.length || 1;

    const catalog = window.TtsVoiceCatalog || [];
    const currentModel = catalog.find(v => v.id === this.tts.selectedAiVoiceId) || { displayName: "Quang Minh" };

    // Đồng bộ giá trị dropdown Giọng AI với model đang chọn
    if (this.dom.ttsFullVoiceSelect && this.dom.ttsFullVoiceSelect.value !== this.tts.selectedAiVoiceId) {
      this.dom.ttsFullVoiceSelect.value = this.tts.selectedAiVoiceId;
    }

    // Mini Player UI
    if (this.dom.ttsMiniText) {
      this.dom.ttsMiniText.textContent = curChunk.text || "Đang đọc...";
    }
    if (this.dom.ttsMiniStatusText) {
      this.dom.ttsMiniStatusText.textContent = this.tts.isPlaying ? `ĐANG PHÁT • ${currentModel.displayName}` : "TẠM DỪNG";
    }
    const pauseIcon = "M6 19h4V5H6v14zm8-14v14h4V5h-4z";
    const playIcon = "M8 5v14l11-7z";
    if (this.dom.ttsMiniPlayIcon) {
      this.dom.ttsMiniPlayIcon.setAttribute("d", this.tts.isPlaying ? pauseIcon : playIcon);
    }

    // Full Player UI
    if (this.dom.ttsFullBookTitle && this.book) {
      this.dom.ttsFullBookTitle.textContent = this.book.title;
      this.dom.ttsFullBookAuthor.textContent = this.book.author || "Khuyết danh";
      const curChap = this.book.chapters[this.currentChapterIndex];
      this.dom.ttsFullChapterTitle.textContent = curChap ? curChap.title : "";
    }
    if (this.dom.ttsFullChunkInfo) {
      this.dom.ttsFullChunkInfo.textContent = `Đoạn ${curIdx + 1} / ${total} • Giọng: ${currentModel.displayName}`;
    }
    if (this.dom.ttsFullParagraphText) {
      this.dom.ttsFullParagraphText.textContent = curChunk.text || "";
    }
    if (this.dom.ttsFullPlayIcon) {
      this.dom.ttsFullPlayIcon.setAttribute("d", this.tts.isPlaying ? pauseIcon : playIcon);
    }

    if (this.dom.ttsAudioVisualizer) {
      this.dom.ttsAudioVisualizer.classList.toggle("playing", this.tts.isPlaying);
    }

    if (this.dom.ttsFullSeekSlider) {
      const percent = Math.min(100, Math.round(((curIdx + 1) / total) * 100));
      this.dom.ttsFullSeekSlider.value = percent;

      const elapsedSec = Math.round((curIdx * 6) / this.tts.speed);
      const totalSec = Math.round((total * 6) / this.tts.speed);
      const fmtTime = (s) => {
        const m = Math.floor(s / 60);
        const sec = s % 60;
        return `${m < 10 ? "0" : ""}${m}:${sec < 10 ? "0" : ""}${sec}`;
      };

      if (this.dom.ttsFullTimeCurrent) this.dom.ttsFullTimeCurrent.textContent = fmtTime(elapsedSec);
      if (this.dom.ttsFullTimeTotal) this.dom.ttsFullTimeTotal.textContent = fmtTime(totalSec);
    }
  }

  pauseTts() {
    if (this.tts.isPlaying) {
      if (this.audioPlayer) {
        try {
          this.audioPlayer.pause();
        } catch (e) {}
      }
      if (this.tts.synth) {
        try {
          this.tts.synth.pause();
        } catch (e) {}
      }
      this.tts.isPlaying = false;
      this.updateTtsUi();
    }
  }

  resumeTts() {
    if (!this.tts.isPlaying) {
      const isSameVoice = this.audioPlayer && this.audioPlayer.src && this.audioPlayer.src.includes(`voice=${this.tts.selectedAiVoiceId}`);
      if (isSameVoice && this.audioPlayer.paused) {
        this.audioPlayer.play().then(() => {
          this.tts.isPlaying = true;
          this.updateTtsUi();
        }).catch(() => {
          this.playTtsChunk(this.tts.currentChunkIndex);
        });
      } else {
        this.playTtsChunk(this.tts.currentChunkIndex);
      }
    }
  }

  toggleTtsPlayPause() {
    if (this.tts.isPlaying) {
      this.pauseTts();
    } else {
      this.resumeTts();
    }
  }

  stopTts() {
    if (this.audioPlayer) {
      try {
        this.audioPlayer.pause();
        this.audioPlayer.currentTime = 0;
        this.audioPlayer.src = "";
      } catch (e) {}
    }
    if (this.tts.synth) {
      try {
        this.tts.synth.cancel();
      } catch (e) {}
    }
    this.tts.isPlaying = false;
    this.subChunks = [];
    this.currentSubChunkIndex = 0;
    document.querySelectorAll(".tts-active-paragraph").forEach(el => el.classList.remove("tts-active-paragraph"));
    if (this.dom.ttsMiniBar) this.dom.ttsMiniBar.classList.remove("active");
    if (this.dom.ttsFullPlayer) this.dom.ttsFullPlayer.classList.remove("active");
    if (this.dom.ttsAudioVisualizer) this.dom.ttsAudioVisualizer.classList.remove("playing");
    if (this.tts.sleepTimerId) {
      clearTimeout(this.tts.sleepTimerId);
      this.tts.sleepTimerId = null;
    }
  }

  openFullTtsPlayer() {
    if (!this.book) {
      this.showToast("Chưa có cuốn sách nào được mở");
      return;
    }
    if (!this.tts.isPlaying && this.tts.chunks.length === 0) {
      this.startTts(0, true);
      return;
    }
    this.closeAllDrawers();
    this.dom.ttsFullPlayer.classList.add("active");
    this.tts.isFullPlayerOpen = true;
    if (this.dom.ttsFullVoiceSelect) {
      this.dom.ttsFullVoiceSelect.value = this.tts.selectedAiVoiceId;
    }
    this.updateTtsUi();
  }

  closeFullTtsPlayer() {
    this.dom.ttsFullPlayer.classList.remove("active");
    this.tts.isFullPlayerOpen = false;
  }

  setTtsSleepTimer(option) {
    if (this.tts.sleepTimerId) {
      clearTimeout(this.tts.sleepTimerId);
      this.tts.sleepTimerId = null;
    }
    this.tts.sleepTimerMode = option;

    if (option === "off") {
      this.showToast("Đã tắt hẹn giờ");
      return;
    }

    if (option === "chapter") {
      this.showToast("Sẽ dừng đọc khi hết chương này");
      return;
    }

    const minutes = parseInt(option, 10);
    if (!isNaN(minutes)) {
      this.tts.sleepTimerId = setTimeout(() => {
        this.stopTts();
        this.showToast(`Hẹn giờ ${minutes} phút đã hoàn tất. Đã dừng đọc.`);
      }, minutes * 60 * 1000);
      this.showToast(`Đã hẹn giờ dừng đọc sau ${minutes} phút`);
    }
  }

  showToast(message) {
    this.dom.toast.textContent = message;
    this.dom.toast.classList.add("show");
    setTimeout(() => {
      this.dom.toast.classList.remove("show");
    }, 2800);
  }

  closeAllDrawers() {
    this.dom.backdrop.classList.remove("active");
    this.dom.drawerToc.classList.remove("active");
    this.dom.drawerSettings.classList.remove("active");
    this.dom.modalUpload.classList.remove("active");
    this.dom.modalBookmarks.classList.remove("active");
    if (this.dom.modalLibrary) {
      this.dom.modalLibrary.classList.remove("active");
    }
    if (this.dom.modalVoiceCatalog) {
      this.dom.modalVoiceCatalog.classList.remove("active");
    }
    if (this.dom.ttsFullPlayer) {
      this.dom.ttsFullPlayer.classList.remove("active");
    }
    if (window.TtsVoiceStorage) {
      window.TtsVoiceStorage.stopPreview();
    }
    if (window.speechSynthesis && this.tts.previewingVoiceId) {
      window.speechSynthesis.cancel();
    }
    this.tts.previewingVoiceId = null;
  }

  openDrawer(drawerEl) {
    this.closeAllDrawers();
    this.dom.backdrop.classList.add("active");
    drawerEl.classList.add("active");
  }

  openLibrary() {
    this.closeAllDrawers();
    this.dom.backdrop.classList.add("active");
    this.dom.modalLibrary.classList.add("active");
    this.renderLibrary();
  }

  /**
   * Xử lý file EPUB được tải lên
   */
  async handleFile(file) {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith(".epub")) {
      this.showToast("Vui lòng chọn tệp định dạng .epub");
      return;
    }

    this.showToast("Đang giải nén và phân tích sách EPUB...");
    try {
      const parsedBook = await this.parser.parse(file);

      parsedBook.id = "book-" + Date.now();
      parsedBook.tag = "EPUB Cá nhân";
      parsedBook.coverGradient = "linear-gradient(135deg, #4F46E5 0%, #06B6D4 100%)";

      // Lưu sách vào IndexedDB
      await this.storage.saveBook(parsedBook);

      // Cập nhật mảng local
      const existingIdx = this.customBooks.findIndex(b => b.title === parsedBook.title);
      if (existingIdx !== -1) {
        this.customBooks[existingIdx] = parsedBook;
      } else {
        this.customBooks.unshift(parsedBook);
      }

      // Mở sách ngay
      this.loadBook(parsedBook, true);
      this.renderLibrary();
      this.closeAllDrawers();
      this.showToast(`Đã thêm "${parsedBook.title}" vào Kệ Sách!`);
    } catch (err) {
      console.error(err);
      this.showToast(`Lỗi mở sách: ${err.message}`);
    }
  }

  /**
   * Thiết lập toàn bộ sự kiện lắng nghe (Keyboard, Click, Gestures)
   */
  bindEvents() {
    // Click Zones trên màn hình đọc
    document.getElementById("zone-left").addEventListener("click", () => this.prevPage());
    document.getElementById("zone-center").addEventListener("click", () => this.toggleControls());
    document.getElementById("zone-right").addEventListener("click", () => this.nextPage());

    // Nút lật trang
    this.dom.btnPrevPage.addEventListener("click", () => this.prevPage());
    this.dom.btnNextPage.addEventListener("click", () => this.nextPage());

    // Nút chuyển chương
    this.dom.btnPrevChapter.addEventListener("click", () => this.prevChapter());
    this.dom.btnNextChapter.addEventListener("click", () => this.nextChapter());

    // Thanh trượt tiến độ sách
    this.dom.progressSlider.addEventListener("input", (e) => {
      if (!this.book || !this.book.chapters) return;
      const val = parseInt(e.target.value);
      const totalChaps = this.book.chapters.length;
      const targetChap = Math.min(totalChaps - 1, Math.floor((val / 100) * totalChaps));
      this.goToChapter(targetChap, 0);
    });

    // Mở Kệ Sách & Lịch Sử (Library)
    if (this.dom.btnOpenLibrary) {
      this.dom.btnOpenLibrary.addEventListener("click", () => this.openLibrary());
    }
    if (this.dom.btnCloseLibrary) {
      this.dom.btnCloseLibrary.addEventListener("click", () => this.closeAllDrawers());
    }

    // Nút upload từ header của Kệ Sách
    if (this.dom.btnLibraryUploadTrigger) {
      this.dom.btnLibraryUploadTrigger.addEventListener("click", () => this.dom.fileInput.click());
    }

    // Xóa toàn bộ lịch sử đọc
    if (this.dom.btnClearHistory) {
      this.dom.btnClearHistory.addEventListener("click", async () => {
        if (confirm("Bạn có chắc chắn muốn xóa toàn bộ lịch sử đọc sách không?")) {
          await this.storage.clearHistory();
          this.readingHistory = [];
          this.renderLibrary();
          this.showToast("Đã xóa sạch lịch sử đọc");
        }
      });
    }

    // Tìm kiếm trong Kệ Sách
    if (this.dom.librarySearchInput) {
      this.dom.librarySearchInput.addEventListener("input", (e) => {
        this.librarySearchQuery = e.target.value;
        this.renderLibrary();
      });
    }

    // Bộ lọc danh mục trong Kệ Sách (Tất cả, Lịch sử, Đang đọc dở, Đã xong)
    document.querySelectorAll(".filter-chip-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        document.querySelectorAll(".filter-chip-btn").forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        this.currentLibraryFilter = btn.getAttribute("data-filter");
        this.renderLibrary();
      });
    });

    // Mở Drawer TOC
    document.getElementById("btn-open-toc").addEventListener("click", () => this.openDrawer(this.dom.drawerToc));
    document.getElementById("btn-close-toc").addEventListener("click", () => this.closeAllDrawers());

    // Mở Drawer Cài Đặt
    document.getElementById("btn-open-settings").addEventListener("click", () => this.openDrawer(this.dom.drawerSettings));
    document.getElementById("btn-close-settings").addEventListener("click", () => this.closeAllDrawers());

    // Mở Modal Upload
    document.getElementById("btn-open-upload").addEventListener("click", () => this.openDrawer(this.dom.modalUpload));
    document.getElementById("btn-close-upload").addEventListener("click", () => this.closeAllDrawers());

    // Mở Modal Bookmarks
    document.getElementById("btn-open-bookmarks").addEventListener("click", () => this.openDrawer(this.dom.modalBookmarks));
    document.getElementById("btn-close-bookmarks").addEventListener("click", () => this.closeAllDrawers());
    document.getElementById("btn-add-bookmark").addEventListener("click", () => this.addBookmark());

    // Click ra Backdrop đóng mọi panel
    this.dom.backdrop.addEventListener("click", () => this.closeAllDrawers());

    // TTS AUDIO EVENTS
    document.getElementById("btn-open-tts").addEventListener("click", () => {
      if (this.tts.isPlaying) {
        this.openFullTtsPlayer();
      } else {
        this.startTts(0, true);
      }
    });

    if (this.dom.ttsMiniPlayBtn) {
      this.dom.ttsMiniPlayBtn.addEventListener("click", () => this.toggleTtsPlayPause());
    }
    if (this.dom.ttsMiniInfoExpand) {
      this.dom.ttsMiniInfoExpand.addEventListener("click", () => this.openFullTtsPlayer());
    }
    if (this.dom.ttsMiniExpandBtn) {
      this.dom.ttsMiniExpandBtn.addEventListener("click", () => this.openFullTtsPlayer());
    }
    if (this.dom.ttsMiniCloseBtn) {
      this.dom.ttsMiniCloseBtn.addEventListener("click", () => this.stopTts());
    }

    if (this.dom.ttsFullMinimizeBtn) {
      this.dom.ttsFullMinimizeBtn.addEventListener("click", () => this.closeFullTtsPlayer());
    }
    if (this.dom.ttsFullPlayBtn) {
      this.dom.ttsFullPlayBtn.addEventListener("click", () => this.toggleTtsPlayPause());
    }
    if (this.dom.ttsFullSkipPrev) {
      this.dom.ttsFullSkipPrev.addEventListener("click", () => {
        this.playTtsChunk(this.tts.currentChunkIndex - 1);
      });
    }
    if (this.dom.ttsFullSkipNext) {
      this.dom.ttsFullSkipNext.addEventListener("click", () => {
        this.playTtsChunk(this.tts.currentChunkIndex + 1);
      });
    }

    if (this.dom.ttsFullSeekSlider) {
      this.dom.ttsFullSeekSlider.addEventListener("input", (e) => {
        const val = parseInt(e.target.value, 10);
        const total = this.tts.chunks.length;
        if (total > 0) {
          const targetIdx = Math.min(total - 1, Math.floor((val / 100) * total));
          this.playTtsChunk(targetIdx);
        }
      });
    }

    if (this.dom.ttsFullSpeedSelect) {
      this.dom.ttsFullSpeedSelect.addEventListener("change", (e) => {
        this.tts.speed = parseFloat(e.target.value);
        if (this.tts.isPlaying) {
          if (this.audioPlayer && !this.audioPlayer.paused) {
            const catalog = window.TtsVoiceCatalog || [];
            const currentModel = catalog.find(v => v.id === this.tts.selectedAiVoiceId) || { id: "ngoc_ngan" };
            let effectiveRate = this.tts.speed;
            if (currentModel.id === "ngoc_ngan") effectiveRate *= 0.92;
            else if (currentModel.id === "quang_minh") effectiveRate *= 0.96;
            else if (currentModel.id === "ban_mai") effectiveRate *= 1.05;
            else if (currentModel.id === "tran_thanh") effectiveRate *= 1.10;
            else if (currentModel.id === "deep_man" || currentModel.id === "duy") effectiveRate *= 0.88;
            this.audioPlayer.playbackRate = Math.min(2.0, Math.max(0.5, effectiveRate));
          } else {
            this.playTtsChunk(this.tts.currentChunkIndex);
          }
        }
      });
    }

    if (this.dom.ttsFullVoiceSelect) {
      this.dom.ttsFullVoiceSelect.addEventListener("change", (e) => {
        const val = e.target.value;
        if (val === "__open_catalog__") {
          this.openVoiceCatalogModal();
          this.dom.ttsFullVoiceSelect.value = this.tts.selectedAiVoiceId;
          return;
        }
        this.tts.selectedAiVoiceId = val;
        this.tts.downloadedVoiceIds.add(val);
        localStorage.setItem("epubpro_selected_ai_voice", val);
        const catalog = window.TtsVoiceCatalog || [];
        const model = catalog.find(v => v.id === val);
        if (model) {
          this.showToast(`Đã chọn giọng AI: ${model.displayName}`);
        }
        this.updateTtsUi();
        if (this.audioPlayer) {
          this.audioPlayer.pause();
          this.audioPlayer.src = "";
        }
        if (this.tts.isPlaying) {
          this.playTtsChunk(this.tts.currentChunkIndex);
        }
      });
    }

    // Modal Kho Giọng Đọc AI Triggers
    if (this.dom.btnOpenVoiceCatalog) {
      this.dom.btnOpenVoiceCatalog.addEventListener("click", () => {
        this.openVoiceCatalogModal();
      });
    }

    if (this.dom.btnCloseVoiceCatalog) {
      this.dom.btnCloseVoiceCatalog.addEventListener("click", () => {
        this.closeVoiceCatalogModal();
      });
    }

    // Bộ lọc danh mục giọng đọc
    const voiceFilterBtns = document.querySelectorAll(".voice-filter-btn");
    voiceFilterBtns.forEach(btn => {
      btn.addEventListener("click", () => {
        voiceFilterBtns.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        const filter = btn.getAttribute("data-filter") || "all";
        this.renderVoiceCatalogCards(filter);
      });
    });

    if (this.dom.ttsFullTimerSelect) {
      this.dom.ttsFullTimerSelect.addEventListener("change", (e) => {
        this.setTtsSleepTimer(e.target.value);
      });
    }

    // Keyboard Shortcuts
    window.addEventListener("keydown", (e) => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA") return;

      switch (e.key) {
        case "ArrowRight":
        case "PageDown":
          e.preventDefault();
          this.nextPage();
          break;
        case "ArrowLeft":
        case "PageUp":
          e.preventDefault();
          this.prevPage();
          break;
        case " ":
          if (this.tts.isPlaying || this.tts.isFullPlayerOpen) {
            e.preventDefault();
            this.toggleTtsPlayPause();
          } else {
            e.preventDefault();
            this.nextPage();
          }
          break;
        case "f":
        case "F":
          if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => {});
          } else {
            document.exitFullscreen().catch(() => {});
          }
          break;
        case "t":
        case "T": {
          const themes = ["light", "sepia", "paper", "dark", "midnight"];
          const curIdx = themes.indexOf(this.settings.themeMode);
          this.settings.themeMode = themes[(curIdx + 1) % themes.length];
          this.saveSettings();
          this.applySettings();
          this.showToast(`Giao diện: ${this.settings.themeMode.toUpperCase()}`);
          break;
        }
        case "l":
        case "L":
          if (this.dom.modalLibrary.classList.contains("active")) {
            this.closeAllDrawers();
          } else {
            this.openLibrary();
          }
          break;
        case "m":
        case "M":
          this.openDrawer(this.dom.drawerToc);
          break;
        case "Escape":
          this.closeAllDrawers();
          break;
      }
    });

    // Theme selector buttons
    document.querySelectorAll(".theme-pill-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        this.settings.themeMode = btn.getAttribute("data-theme-val");
        this.saveSettings();
        this.applySettings();
      });
    });

    // Font family chips
    document.querySelectorAll(".chip-font-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        this.settings.fontFamily = btn.getAttribute("data-font-val");
        this.saveSettings();
        this.applySettings();
      });
    });

    // Sliders
    const fsSlider = document.getElementById("slider-font-size");
    if (fsSlider) {
      fsSlider.addEventListener("input", (e) => {
        this.settings.fontSizePx = parseInt(e.target.value);
        this.saveSettings();
        this.applySettings();
      });
    }

    const lhSlider = document.getElementById("slider-line-height");
    if (lhSlider) {
      lhSlider.addEventListener("input", (e) => {
        this.settings.lineHeight = parseFloat(e.target.value);
        this.saveSettings();
        this.applySettings();
      });
    }

    const mgSlider = document.getElementById("slider-margin");
    if (mgSlider) {
      mgSlider.addEventListener("input", (e) => {
        this.settings.marginPx = parseInt(e.target.value);
        this.saveSettings();
        this.applySettings();
      });
    }

    document.querySelectorAll(".chip-align-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        this.settings.textAlignment = btn.getAttribute("data-align-val");
        this.saveSettings();
        this.applySettings();
      });
    });

    document.querySelectorAll(".seg-mode-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        this.settings.readingMode = btn.getAttribute("data-mode-val");
        this.saveSettings();
        this.applySettings();
        if (this.book) {
          this.renderChapter(this.currentChapterIndex, 0);
        }
      });
    });

    // File Input & Drag and Drop EPUB
    this.dom.dropZone.addEventListener("click", () => this.dom.fileInput.click());
    if (this.dom.welcomeDropzone) {
      this.dom.welcomeDropzone.addEventListener("click", () => this.dom.fileInput.click());
    }
    this.dom.fileInput.addEventListener("change", (e) => {
      if (e.target.files && e.target.files[0]) {
        this.handleFile(e.target.files[0]);
      }
    });

    window.addEventListener("dragover", (e) => e.preventDefault());
    window.addEventListener("drop", (e) => {
      e.preventDefault();
      if (e.dataTransfer.files && e.dataTransfer.files[0]) {
        this.handleFile(e.dataTransfer.files[0]);
      }
    });

    // Window Resize
    window.addEventListener("resize", () => {
      if (this.book && this.settings.readingMode === "paged") {
        this.updatePagedMetrics(this.currentPageIndex);
      }
    });
  }
}

// Khởi chạy khi tài liệu tải xong
document.addEventListener("DOMContentLoaded", () => {
  window.epubProReader = new EpubProReader();
});
