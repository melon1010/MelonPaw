// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  DownloadCancelledError,
  downloadFileFromUrl,
} from "./downloadFileFromUrl";
import { openExternalLink } from "./openExternalLink";

describe("openExternalLink", () => {
  const windowOpen = vi.fn();
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:download"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    windowOpen.mockReset();
    vi.spyOn(window, "open").mockImplementation(windowOpen);
    delete (window as any).pywebview;
    localStorage.clear();
    (globalThis as any).VITE_API_BASE_URL = "";
    (globalThis as any).TOKEN = "";
    window.history.replaceState(null, "", "/");
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("uses the pywebview bridge for legacy HTTP links", () => {
    const openExternal = vi.fn();
    (window as any).pywebview = {
      api: { open_external_link: openExternal },
    };

    openExternalLink("https://github.com/agentscope-ai/QwenPaw");

    expect(openExternal).toHaveBeenCalledWith(
      "https://github.com/agentscope-ai/QwenPaw",
    );
    expect(windowOpen).not.toHaveBeenCalled();
  });

  it("opens supported non-HTTP links in the browser", () => {
    openExternalLink("mailto:support@example.com");

    expect(windowOpen).toHaveBeenCalledWith(
      "mailto:support@example.com",
      "_blank",
      "noopener,noreferrer",
    );
  });

  it("ignores unsafe or fragment-only links", () => {
    openExternalLink("javascript:alert(1)");
    openExternalLink("#");

    expect(windowOpen).not.toHaveBeenCalled();
  });

  it("rejects ambiguous HTTP links without slashes before opening", () => {
    openExternalLink("http:example.com");

    expect(windowOpen).not.toHaveBeenCalled();
  });

  it("falls back to window.open in the web console", () => {
    openExternalLink("https://qwenpaw.agentscope.io/docs/intro?lang=en");

    expect(windowOpen).toHaveBeenCalledWith(
      "https://qwenpaw.agentscope.io/docs/intro?lang=en",
      "_blank",
      "noopener,noreferrer",
    );
  });

  it("resolves relative links before passing them to desktop bridges", () => {
    const openExternal = vi.fn();
    (window as any).pywebview = {
      api: { open_external_link: openExternal },
    };

    openExternalLink("/docs/faq");

    expect(openExternal).toHaveBeenCalledWith("http://localhost:3000/docs/faq");
  });

  it("uses the pywebview save bridge for legacy desktop downloads", async () => {
    const saveFile = vi.fn().mockResolvedValue(true);
    (window as any).pywebview = {
      api: { save_file: saveFile },
    };

    await expect(
      downloadFileFromUrl(
        "/api/backups/abc/export",
        "Backup 2026-05-22 14:13.zip",
        {
          headers: { Authorization: "Bearer tok" },
          errorMessage: "Export failed",
        },
      ),
    ).resolves.toBeUndefined();

    expect(saveFile).toHaveBeenCalledWith(
      "http://localhost:3000/api/backups/abc/export",
      "Backup 2026-05-22 14_13.zip",
      { Authorization: "Bearer tok" },
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports pywebview download cancellation", async () => {
    const saveFile = vi.fn().mockResolvedValue(false);
    (window as any).pywebview = {
      api: { save_file: saveFile },
    };

    await expect(
      downloadFileFromUrl("/api/workspace/download", "workspace.zip"),
    ).rejects.toBeInstanceOf(DownloadCancelledError);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("uses browser downloads without a desktop bridge", async () => {
    fetchMock.mockResolvedValue(
      new Response("zip", {
        headers: {
          "Content-Disposition": "attachment; filename*=UTF-8''server.zip",
        },
      }),
    );
    const click = vi.fn();
    const createElement = vi.spyOn(document, "createElement");
    createElement.mockImplementation((tagName: string) => {
      const element = document.createElementNS(
        "http://www.w3.org/1999/xhtml",
        tagName,
      ) as HTMLElement;
      if (tagName === "a") {
        element.click = click;
      }
      return element;
    });

    await expect(
      downloadFileFromUrl("/api/backups/abc/export", "backup.zip", {
        preferResponseFilename: true,
      }),
    ).resolves.toBeUndefined();

    expect(click).toHaveBeenCalled();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:download");
  });
});
