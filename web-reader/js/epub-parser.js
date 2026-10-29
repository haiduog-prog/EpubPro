/**
 * Trình phân tích tệp EPUB chạy hoàn toàn phía Client (Trình duyệt)
 * Sử dụng thư viện JSZip để giải nén và phân tích cấu trúc OPF / Spine / TOC
 */
class EpubParser {
  constructor() {
    this.zip = null;
    this.opfPath = "";
    this.baseDir = "";
    this.manifest = new Map();
    this.spine = [];
    this.toc = [];
    this.metadata = {};
    this.blobUrls = [];
  }

  /**
   * Giải phóng các Blob URLs đã tạo cho hình ảnh khi tải sách mới
   */
  cleanup() {
    for (const url of this.blobUrls) {
      try {
        URL.revokeObjectURL(url);
      } catch (e) {
        console.warn("Lỗi revoke blob url:", e);
      }
    }
    this.blobUrls = [];
  }

  /**
   * Phân tích tệp EPUB từ File (Drag & Drop hoặc Input) hoặc ArrayBuffer
   * @param {File|Blob|ArrayBuffer} data
   * @returns {Promise<Object>} Cấu trúc sách đã chuẩn hóa
   */
  async parse(data) {
    if (typeof JSZip === "undefined") {
      throw new Error("Thư viện JSZip chưa được tải. Vui lòng kiểm tra kết nối mạng hoặc đính kèm JSZip cục bộ.");
    }

    this.cleanup();
    this.manifest.clear();
    this.spine = [];
    this.toc = [];
    this.metadata = {};

    this.zip = await JSZip.loadAsync(data);

    // 1. Tìm container.xml
    const containerEntry = this.zip.file("META-INF/container.xml");
    if (!containerEntry) {
      throw new Error("Không tìm thấy META-INF/container.xml hợp lệ trong tệp EPUB");
    }

    const containerXmlText = await containerEntry.async("text");
    const parser = new DOMParser();
    const containerDoc = parser.parseFromString(containerXmlText, "application/xml");
    const rootfileEl = containerDoc.querySelector("rootfile");
    if (!rootfileEl) {
      throw new Error("Không tìm thấy thẻ rootfile trong container.xml");
    }

    this.opfPath = rootfileEl.getAttribute("full-path");
    const lastSlash = this.opfPath.lastIndexOf("/");
    this.baseDir = lastSlash !== -1 ? this.opfPath.substring(0, lastSlash + 1) : "";

    // 2. Đọc file OPF
    const opfEntry = this.zip.file(this.opfPath);
    if (!opfEntry) {
      throw new Error(`Không tìm thấy file OPF tại đường dẫn: ${this.opfPath}`);
    }

    const opfXmlText = await opfEntry.async("text");
    const opfDoc = parser.parseFromString(opfXmlText, "application/xml");

    // Metadata
    this.metadata = this._parseMetadata(opfDoc);

    // Manifest
    this._parseManifest(opfDoc);

    // Spine
    this._parseSpine(opfDoc);

    // TOC
    await this._parseToc(opfDoc);

    // 3. Đọc và chuẩn hóa nội dung các chương theo thứ tự spine
    const chapters = await this._loadChapters();

    return {
      title: this.metadata.title || "Tác phẩm chưa đặt tên",
      author: this.metadata.creator || "Tác giả khuyết danh",
      publisher: this.metadata.publisher || "EpubPro Web Reader",
      description: this.metadata.description || "",
      cover: await this._loadCover(),
      chapters: chapters
    };
  }

  _parseMetadata(opfDoc) {
    const meta = {};
    const titleEl = opfDoc.querySelector("title, dc\\:title");
    meta.title = titleEl ? titleEl.textContent.trim() : "Chưa có tiêu đề";

    const creatorEl = opfDoc.querySelector("creator, dc\\:creator");
    meta.creator = creatorEl ? creatorEl.textContent.trim() : "Khuyết danh";

    const publisherEl = opfDoc.querySelector("publisher, dc\\:publisher");
    meta.publisher = publisherEl ? publisherEl.textContent.trim() : "";

    const descEl = opfDoc.querySelector("description, dc\\:description");
    meta.description = descEl ? descEl.textContent.trim() : "";

    return meta;
  }

  _parseManifest(opfDoc) {
    const items = opfDoc.querySelectorAll("manifest > item");
    for (const item of items) {
      const id = item.getAttribute("id");
      const href = item.getAttribute("href");
      const mediaType = item.getAttribute("media-type");
      const properties = item.getAttribute("properties") || "";

      // Giải quyết đường dẫn tương đối so với baseDir của file OPF
      const fullPath = this._resolvePath(this.baseDir, href);

      this.manifest.set(id, {
        id,
        href,
        fullPath,
        mediaType,
        properties
      });
    }
  }

  _parseSpine(opfDoc) {
    const itemrefs = opfDoc.querySelectorAll("spine > itemref");
    for (const itemref of itemrefs) {
      const idref = itemref.getAttribute("idref");
      if (this.manifest.has(idref)) {
        this.spine.push(this.manifest.get(idref));
      }
    }
  }

  async _parseToc(opfDoc) {
    // Thử tìm EPUB3 nav doc trước
    let navItem = null;
    for (const item of this.manifest.values()) {
      if (item.properties.includes("nav")) {
        navItem = item;
        break;
      }
    }

    if (navItem) {
      try {
        const file = this.zip.file(navItem.fullPath);
        if (file) {
          const text = await file.async("text");
          const parser = new DOMParser();
          const doc = parser.parseFromString(text, "text/html");
          const links = doc.querySelectorAll("nav[epub\\:type='toc'] a, nav a");
          for (const a of links) {
            const rawHref = a.getAttribute("href");
            const cleanHref = rawHref.split("#")[0];
            const title = a.textContent.trim();
            if (title && cleanHref) {
              this.toc.push({ title, href: cleanHref });
            }
          }
        }
      } catch (e) {
        console.warn("Không parse được nav doc:", e);
      }
    }

    // Nếu chưa có, thử tìm EPUB2 toc.ncx
    if (this.toc.length === 0) {
      let ncxItem = null;
      for (const item of this.manifest.values()) {
        if (item.mediaType === "application/x-dtbncx+xml" || item.id === "ncx" || item.href.endsWith(".ncx")) {
          ncxItem = item;
          break;
        }
      }

      if (ncxItem) {
        try {
          const file = this.zip.file(ncxItem.fullPath);
          if (file) {
            const text = await file.async("text");
            const parser = new DOMParser();
            const doc = parser.parseFromString(text, "application/xml");
            const navPoints = doc.querySelectorAll("navPoint");
            for (const np of navPoints) {
              const textEl = np.querySelector("text");
              const contentEl = np.querySelector("content");
              if (textEl && contentEl) {
                const title = textEl.textContent.trim();
                const src = contentEl.getAttribute("src") || "";
                const cleanHref = src.split("#")[0];
                this.toc.push({ title, href: cleanHref });
              }
            }
          }
        } catch (e) {
          console.warn("Không parse được NCX TOC:", e);
        }
      }
    }
  }

  async _loadCover() {
    // Tìm cover trong manifest properties
    let coverItem = null;
    for (const item of this.manifest.values()) {
      if (item.properties.includes("cover-image") || item.id.toLowerCase().includes("cover")) {
        if (item.mediaType.startsWith("image/")) {
          coverItem = item;
          break;
        }
      }
    }

    if (coverItem) {
      const file = this.zip.file(coverItem.fullPath);
      if (file) {
        const blob = await file.async("blob");
        const url = URL.createObjectURL(blob);
        this.blobUrls.push(url);
        return url;
      }
    }
    return null;
  }

  async _loadChapters() {
    const chapters = [];
    const parser = new DOMParser();

    for (let i = 0; i < this.spine.length; i++) {
      const item = this.spine[i];
      const file = this.zip.file(item.fullPath);
      if (!file) continue;

      let htmlText = await file.async("text");

      // Tìm tên chương từ TOC hoặc từ thẻ h1/h2/title trong chính văn bản
      let chapterTitle = this._findTocTitle(item.href) || `Chương ${i + 1}`;

      // Xử lý sanitize an toàn và giải quyết đường dẫn ảnh nhúng
      const itemDir = item.fullPath.substring(0, item.fullPath.lastIndexOf("/") + 1);
      const doc = parser.parseFromString(htmlText, "text/html");

      // Lấy title nếu chưa có từ TOC
      if (chapterTitle.startsWith("Chương ")) {
        const headerEl = doc.querySelector("h1, h2, h3, title");
        if (headerEl && headerEl.textContent.trim().length > 0) {
          chapterTitle = headerEl.textContent.trim();
        }
      }

      // Xử lý các thẻ ảnh (img, svg image, picture)
      await this._resolveEmbeddedImages(doc, itemDir);

      // Loại bỏ các thẻ script và iframe độc hại theo quy chuẩn bảo mật EpubPro
      const scripts = doc.querySelectorAll("script, iframe, object, embed, link[rel='stylesheet']");
      scripts.forEach(el => el.remove());

      const bodyContent = doc.body ? doc.body.innerHTML : htmlText;

      chapters.push({
        id: item.id || `chapter-${i}`,
        title: chapterTitle,
        content: bodyContent
      });
    }

    return chapters;
  }

  _findTocTitle(href) {
    const clean = href.split("#")[0].split("/").pop();
    for (const item of this.toc) {
      const itemClean = item.href.split("/").pop();
      if (itemClean === clean) {
        return item.title;
      }
    }
    return null;
  }

  async _resolveEmbeddedImages(doc, itemDir) {
    const imgElements = doc.querySelectorAll("img, image");
    for (const img of imgElements) {
      const srcAttr = img.tagName.toLowerCase() === "image" ? "xlink:href" : "src";
      let src = img.getAttribute(srcAttr);
      if (!src || src.startsWith("data:") || src.startsWith("http")) continue;

      // Đường dẫn tuyệt đối trong file zip
      const imagePath = this._resolvePath(itemDir, src);
      const zipFile = this.zip.file(imagePath);
      if (zipFile) {
        try {
          const blob = await zipFile.async("blob");
          const blobUrl = URL.createObjectURL(blob);
          this.blobUrls.push(blobUrl);
          img.setAttribute(srcAttr, blobUrl);
          if (img.tagName.toLowerCase() === "image") {
            img.setAttribute("href", blobUrl);
          }
        } catch (e) {
          console.warn("Lỗi trích xuất ảnh:", imagePath, e);
        }
      }
    }
  }

  _resolvePath(base, relative) {
    if (relative.startsWith("/")) {
      return relative.substring(1);
    }
    const stack = base ? base.split("/").filter(Boolean) : [];
    const parts = relative.split("/");

    for (const part of parts) {
      if (part === "" || part === ".") continue;
      if (part === "..") {
        if (stack.length > 0) stack.pop();
      } else {
        stack.push(part);
      }
    }
    return stack.join("/");
  }
}

window.EpubParser = EpubParser;
