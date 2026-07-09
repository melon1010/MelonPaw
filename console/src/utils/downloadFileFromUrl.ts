/**
 * Cross-runtime file download helper for browser and the legacy pywebview shell.
 */
import { isHttpExternalUrl, resolveExternalUrl } from "./openExternalLink";
import { getPyWebViewApi, type PyWebViewSaveFile } from "./pywebview";

export interface DownloadFileOptions {
  headers?: Record<string, string>;
  errorMessage?: string;
  /**
   * Prefer Content-Disposition filenames when the browser path fetches the file.
   * Native desktop paths use the fallback filename shown in the save dialog.
   */
  preferResponseFilename?: boolean;
}

export class DownloadCancelledError extends Error {
  constructor() {
    super("Download cancelled");
    this.name = "DownloadCancelledError";
  }
}

/** Extract a suggested filename from the server's Content-Disposition header. */
function filenameFromContentDisposition(value: string | null): string {
  if (!value) return "";

  const utf8Match = value.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }

  const quotedMatch = value.match(/filename="([^"]+)"/i);
  if (quotedMatch?.[1]) {
    return quotedMatch[1];
  }

  const bareMatch = value.match(/filename=([^;]+)/i);
  return bareMatch?.[1]?.trim() ?? "";
}

/** Trigger a normal browser download by clicking a temporary blob-backed link. */
function triggerBrowserDownload(blob: Blob, filename: string): void {
  const a = document.createElement("a");
  const objectUrl = URL.createObjectURL(blob);
  a.href = objectUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(objectUrl);
    a.remove();
  }, 0);
}

/** Normalize suggested filenames for native save dialogs across platforms. */
function sanitizeSaveFilename(filename: string): string {
  // Use Windows-safe names for native dialogs so suggested filenames work
  // across both packaged desktop shells and all supported OS file systems.
  const sanitized = filename
    .replace(/[<>:"/\\|?*]/g, "_")
    .trim()
    .replace(/[. ]+$/g, "");
  return sanitized || "download";
}

/** Save through the legacy pywebview bridge used by the old desktop package. */
async function downloadWithPyWebView(
  saveFile: PyWebViewSaveFile,
  url: string,
  filename: string,
  options: DownloadFileOptions,
): Promise<void> {
  const headers = options.headers ?? {};
  const saved =
    Object.keys(headers).length > 0
      ? await saveFile(url, filename, headers)
      : await saveFile(url, filename);
  if (!saved) {
    throw new DownloadCancelledError();
  }
}

/** Save in a regular browser by fetching a blob and clicking a download link. */
async function downloadWithBrowser(
  url: string,
  filename: string,
  options: DownloadFileOptions,
): Promise<void> {
  const res = await fetch(url, { headers: options.headers });
  if (!res.ok) {
    const error = new Error(
      options.errorMessage || `Download failed: ${res.status}`,
    ) as Error & { status?: number };
    error.status = res.status;
    throw error;
  }

  const responseFilename = options.preferResponseFilename
    ? filenameFromContentDisposition(res.headers.get("Content-Disposition"))
    : "";
  triggerBrowserDownload(
    await res.blob(),
    responseFilename ? sanitizeSaveFilename(responseFilename) : filename,
  );
}

/** Download a URL using the best available runtime path: pywebview or browser. */
export async function downloadFileFromUrl(
  url: string,
  filename: string,
  options: DownloadFileOptions = {},
): Promise<void> {
  if (!url) {
    throw new Error(options.errorMessage || "Download URL is empty");
  }

  const requestUrl = resolveExternalUrl(url);
  if (!requestUrl || !isHttpExternalUrl(requestUrl)) {
    throw new Error(options.errorMessage || "Download URL is invalid");
  }

  const safeFilename = sanitizeSaveFilename(filename);
  const pywebviewSaveFile = getPyWebViewApi()?.save_file;
  if (pywebviewSaveFile) {
    await downloadWithPyWebView(
      pywebviewSaveFile,
      requestUrl,
      safeFilename,
      options,
    );
    return;
  }

  await downloadWithBrowser(requestUrl, safeFilename, options);
}
