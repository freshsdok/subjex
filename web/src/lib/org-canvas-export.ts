/**
 * Export org canvas SVG/PNG (Should S3) — client-side from an SVG element.
 * 从 SVG 元素导出组织图（Should S3）。
 */

/** Replace CSS variables / currentColor so standalone SVG/PNG stay readable. */
export function bakeOrgSvgColors(xml: string): string {
  let out = xml;
  // Selected boxes use stroke-width="2" with var(--foreground) fill.
  out = out.replace(
    /fill="var\(--foreground\)"([^>]*stroke-width="2")/g,
    'fill="#111827"$1',
  );
  out = out.replace(
    /(stroke-width="2"[^>]*?)fill="var\(--foreground\)"/g,
    '$1fill="#111827"',
  );
  out = out.replace(/fill="var\(--foreground\)"/g, 'fill="#111827"');
  out = out.replace(/fill="var\(--surface[^"]*"\)"/g, 'fill="#ffffff"');
  out = out.replace(/fill="var\(--background[^"]*"\)"/g, 'fill="#ffffff"');
  out = out.replace(/fill="currentColor"/g, 'fill="#111827"');
  out = out.replace(/stroke="currentColor"/g, 'stroke="#374151"');
  // Lines often use opacity; keep stroke readable
  out = out.replace(/<line([^>]*)stroke="#374151"/g, (m) =>
    m.includes("stroke-opacity") ? m.replace('stroke="#374151"', 'stroke="#6b7280"') : m.replace('stroke="#374151"', 'stroke="#6b7280"'),
  );
  return out;
}

/** Ensure XML declaration + svg xmlns. */
export function wrapOrgSvgXml(xml: string): string {
  let out = xml.trim();
  if (!out.includes("xmlns=")) {
    out = out.replace(/<svg\b/, '<svg xmlns="http://www.w3.org/2000/svg"');
  }
  if (!out.startsWith("<?xml")) {
    out = `<?xml version="1.0" encoding="UTF-8"?>\n${out}`;
  }
  return out;
}

/** Serialize SVG with explicit size/xmlns for standalone download. */
export function serializeOrgSvg(svg: SVGSVGElement): string {
  const clone = svg.cloneNode(true) as SVGSVGElement;
  if (!clone.getAttribute("xmlns")) {
    clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
  }
  const width = clone.getAttribute("width") || String(svg.clientWidth || 800);
  const height = clone.getAttribute("height") || String(svg.clientHeight || 600);
  clone.setAttribute("width", width);
  clone.setAttribute("height", height);
  clone.querySelectorAll("rect").forEach((rect) => {
    const fill = rect.getAttribute("fill") || "";
    if (fill.includes("var(")) {
      const selected = (rect.getAttribute("stroke-width") || "") === "2";
      rect.setAttribute("fill", selected ? "#111827" : "#ffffff");
    }
    const stroke = rect.getAttribute("stroke") || "";
    if (stroke === "currentColor") {
      rect.setAttribute("stroke", "#374151");
    }
  });
  clone.querySelectorAll("text").forEach((text) => {
    const fill = text.getAttribute("fill") || "";
    if (fill.includes("var(") || fill === "currentColor") {
      const parent = text.closest("g");
      const rect = parent?.querySelector("rect");
      const selected = (rect?.getAttribute("stroke-width") || "") === "2";
      text.setAttribute("fill", selected ? "#ffffff" : "#111827");
    }
  });
  clone.querySelectorAll("line").forEach((line) => {
    if ((line.getAttribute("stroke") || "") === "currentColor") {
      line.setAttribute("stroke", "#6b7280");
    }
  });
  const xml = new XMLSerializer().serializeToString(clone);
  return wrapOrgSvgXml(xml);
}

export function downloadTextFile(filename: string, content: string, mime: string): void {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function downloadOrgSvg(svg: SVGSVGElement, filename: string): void {
  const xml = serializeOrgSvg(svg);
  downloadTextFile(filename.endsWith(".svg") ? filename : `${filename}.svg`, xml, "image/svg+xml;charset=utf-8");
}

/**
 * Rasterize SVG to PNG via canvas. Returns a Promise that resolves when download starts.
 */
export function downloadOrgPng(svg: SVGSVGElement, filename: string): Promise<void> {
  const xml = serializeOrgSvg(svg);
  const width = Number(svg.getAttribute("width") || svg.clientWidth || 800);
  const height = Number(svg.getAttribute("height") || svg.clientHeight || 600);
  const url = URL.createObjectURL(new Blob([xml], { type: "image/svg+xml;charset=utf-8" }));
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      try {
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        if (!ctx) {
          URL.revokeObjectURL(url);
          reject(new Error("canvas"));
          return;
        }
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, width, height);
        ctx.drawImage(img, 0, 0, width, height);
        URL.revokeObjectURL(url);
        canvas.toBlob((blob) => {
          if (!blob) {
            reject(new Error("png"));
            return;
          }
          const pngUrl = URL.createObjectURL(blob);
          const a = document.createElement("a");
          a.href = pngUrl;
          a.download = filename.endsWith(".png") ? filename : `${filename}.png`;
          a.click();
          URL.revokeObjectURL(pngUrl);
          resolve();
        }, "image/png");
      } catch (err) {
        URL.revokeObjectURL(url);
        reject(err);
      }
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("image"));
    };
    img.src = url;
  });
}
