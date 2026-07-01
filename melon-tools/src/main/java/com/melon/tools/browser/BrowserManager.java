package com.melon.tools.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Playwright instances and browser sessions.
 * Provides isolated user data directories for each browser type
 * to ensure session isolation between different automation tasks.
 */
public class BrowserManager {

    private volatile Playwright playwright;
    private final Map<String, Browser> browsers = new ConcurrentHashMap<>();
    private final Map<String, BrowserContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private final String userDataBaseDir;
    private final Object lock = new Object();

    public BrowserManager() {
        this.userDataBaseDir = System.getProperty("java.io.tmpdir")
            + java.io.File.separator + "melon-browser-data";
    }

    /**
     * Gets or creates a Playwright instance (lazy initialization, thread-safe).
     *
     * @return the Playwright instance
     */
    public Playwright getPlaywright() {
        if (playwright == null) {
            synchronized (lock) {
                if (playwright == null) {
                    playwright = Playwright.create();
                }
            }
        }
        return playwright;
    }

    /**
     * Gets or creates a browser of the specified type.
     * Each browser type gets an isolated user data directory.
     *
     * @param type the browser type ("chromium", "firefox", "webkit")
     * @return the Browser instance
     */
    public Browser getBrowser(String type) {
        return browsers.computeIfAbsent(type, t -> {
            Playwright pw = getPlaywright();
            BrowserType browserType = switch (t.toLowerCase()) {
                case "firefox" -> pw.firefox();
                case "webkit" -> pw.webkit();
                default -> pw.chromium();
            };

            // Create isolated user data directory for this browser type
            Path userDataDir = Paths.get(userDataBaseDir, t);
            try {
                Files.createDirectories(userDataDir);
            } catch (Exception e) {
                // Fallback: use default profile, continue without persistent data
            }

            // Launch browser with headless mode and common args
            return browserType.launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(java.util.List.of(
                    "--no-sandbox",
                    "--disable-blink-features=AutomationControlled",
                    "--disable-dev-shm-usage",
                    "--disable-gpu"
                ))
            );
        });
    }

    /**
     * Gets or creates a browser context for the specified browser type.
     *
     * @param type the browser type
     * @return the BrowserContext instance
     */
    public BrowserContext getContext(String type) {
        return contexts.computeIfAbsent(type, t -> {
            Browser browser = getBrowser(t);
            return browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setLocale("en-US")
            );
        });
    }

    /**
     * Gets or creates the default page for the chromium browser.
     * If no page exists, a new one is created in the default context.
     *
     * @return the Page instance
     */
    public Page getPage() {
        return getPage("chromium");
    }

    /**
     * Gets or creates a page for the specified browser type.
     * If no page exists, a new one is created in the browser context.
     *
     * @param type the browser type
     * @return the Page instance
     */
    public Page getPage(String type) {
        return pages.computeIfAbsent(type, t -> {
            BrowserContext context = getContext(t);
            return context.newPage();
        });
    }

    /**
     * Creates a new page in the default browser context.
     *
     * @return a new Page instance
     */
    public Page newPage() {
        return newPage("chromium");
    }

    /**
     * Creates a new page in the specified browser context.
     *
     * @param type the browser type
     * @return a new Page instance
     */
    public Page newPage(String type) {
        BrowserContext context = getContext(type);
        Page page = context.newPage();
        pages.put(type, page);
        return page;
    }

    /**
     * Closes all pages, contexts, browser instances and the Playwright runtime.
     */
    public void close() {
        pages.values().forEach(page -> {
            try { page.close(); } catch (Exception ignored) {}
        });
        contexts.values().forEach(context -> {
            try { context.close(); } catch (Exception ignored) {}
        });
        browsers.values().forEach(browser -> {
            try { browser.close(); } catch (Exception ignored) {}
        });
        if (playwright != null) {
            try { playwright.close(); } catch (Exception ignored) {}
            playwright = null;
        }
        pages.clear();
        contexts.clear();
        browsers.clear();
    }
}
