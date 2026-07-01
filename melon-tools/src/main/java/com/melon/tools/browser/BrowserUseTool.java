package com.melon.tools.browser;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Map;

/**
 * Tool for browser automation using Playwright Java.
 * Uses an action dispatch pattern to handle various browser actions:
 * start, stop, navigate, click, type, screenshot, snapshot, eval, etc.
 *
 * Browser instances are managed by {@link BrowserManager} which provides
 * isolated user data directories for session isolation.
 */
public class BrowserUseTool extends ToolBase {

    private final BrowserManager browserManager;

    public BrowserUseTool() {
        super(ToolBase.builder()
            .name("browser_use")
            .description("Perform browser automation actions using Playwright. "
                + "Supports actions: start, stop, navigate, click, type, screenshot, "
                + "snapshot (ARIA tree), eval (JavaScript evaluation), and more. "
                + "Each browser session uses an isolated user data directory.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "description": "Browser action to perform: start, stop, navigate, click, type, screenshot, snapshot, eval, scroll, back, forward, wait",
                      "enum": ["start", "stop", "navigate", "click", "type", "screenshot", "snapshot", "eval", "scroll", "back", "forward", "wait"]
                    },
                    "url": {
                      "type": "string",
                      "description": "URL to navigate to (for navigate action)"
                    },
                    "selector": {
                      "type": "string",
                      "description": "CSS selector for element (for click, type, wait, scroll actions)"
                    },
                    "text": {
                      "type": "string",
                      "description": "Text to type (for type action)"
                    },
                    "script": {
                      "type": "string",
                      "description": "JavaScript to evaluate (for eval action)"
                    },
                    "timeout": {
                      "type": "number",
                      "description": "Timeout in seconds",
                      "default": 30
                    },
                    "scroll_dx": {
                      "type": "number",
                      "description": "Horizontal scroll amount",
                      "default": 0
                    },
                    "scroll_dy": {
                      "type": "number",
                      "description": "Vertical scroll amount",
                      "default": 300
                    }
                  },
                  "required": ["action"]
                }"""))
            .readOnly(false)
            .concurrencySafe(false));
        this.browserManager = new BrowserManager();
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String action = (String) param.getInput().get("action");
        String url = param.getInput().containsKey("url") ? (String) param.getInput().get("url") : null;
        String selector = param.getInput().containsKey("selector") ? (String) param.getInput().get("selector") : null;
        String text = param.getInput().containsKey("text") ? (String) param.getInput().get("text") : null;
        Double timeout = param.getInput().containsKey("timeout") ? ((Number) param.getInput().get("timeout")).doubleValue() : 30.0;

        return Mono.fromCallable(() -> {
            return switch (action) {
                case "start" -> handleStart();
                case "stop" -> handleStop();
                case "navigate" -> handleNavigate(url);
                case "click" -> handleClick(selector, timeout);
                case "type" -> handleType(selector, text);
                case "screenshot" -> handleScreenshot();
                case "snapshot" -> handleSnapshot();
                case "eval" -> handleEval((String) param.getInput().get("script"));
                case "scroll" -> handleScroll(selector, param);
                case "back" -> handleBack();
                case "forward" -> handleForward();
                case "wait" -> handleWait(selector, timeout);
                default -> ToolResultBlock.error("Unknown browser action: " + action);
            };
        });
    }

    private ToolResultBlock handleStart() {
        try {
            browserManager.getPlaywright();
            browserManager.getBrowser("chromium");
            browserManager.getPage("chromium");
            return ToolResultBlock.text("Browser started successfully.");
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to start browser: " + e.getMessage());
        }
    }

    private ToolResultBlock handleStop() {
        try {
            browserManager.close();
            return ToolResultBlock.text("Browser stopped successfully.");
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to stop browser: " + e.getMessage());
        }
    }

    private ToolResultBlock handleNavigate(String url) {
        if (url == null || url.isBlank()) {
            return ToolResultBlock.error("url is required for navigate action");
        }
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            com.microsoft.playwright.Response response = page.navigate(url);
            String title = page.title();
            String msg = "Navigated to: " + url + "\nTitle: " + title;
            if (response != null) {
                msg += "\nStatus: " + response.status();
            }
            return ToolResultBlock.text(msg);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to navigate: " + e.getMessage());
        }
    }

    private ToolResultBlock handleClick(String selector, Double timeout) {
        if (selector == null || selector.isBlank()) {
            return ToolResultBlock.error("selector is required for click action");
        }
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            double timeoutMs = (timeout != null ? timeout : 30.0) * 1000;
            page.click(selector, new com.microsoft.playwright.Page.ClickOptions()
                .setTimeout(timeoutMs));
            return ToolResultBlock.text("Clicked: " + selector);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to click '" + selector + "': " + e.getMessage());
        }
    }

    private ToolResultBlock handleType(String selector, String text) {
        if (selector == null || selector.isBlank()) {
            return ToolResultBlock.error("selector is required for type action");
        }
        if (text == null) {
            text = "";
        }
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            page.fill(selector, text);
            return ToolResultBlock.text("Typed text into: " + selector);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to type into '" + selector + "': " + e.getMessage());
        }
    }

    private ToolResultBlock handleScreenshot() {
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            byte[] screenshotBytes = page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                .setFullPage(true));
            String base64 = Base64.getEncoder().encodeToString(screenshotBytes);
            return ToolResultBlock.text("Screenshot captured (base64, " + screenshotBytes.length + " bytes):\n" + base64);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to take screenshot: " + e.getMessage());
        }
    }

    private ToolResultBlock handleSnapshot() {
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            String snapshot = BrowserSnapshotUtil.buildSnapshot(page);
            return ToolResultBlock.text(snapshot);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to build snapshot: " + e.getMessage());
        }
    }

    private ToolResultBlock handleEval(String script) {
        if (script == null || script.isBlank()) {
            return ToolResultBlock.error("script is required for eval action");
        }
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            Object result = page.evaluate(script);
            String resultStr = result != null ? result.toString() : "undefined";
            return ToolResultBlock.text("JavaScript evaluated. Result: " + resultStr);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to evaluate script: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResultBlock handleScroll(String selector, ToolCallParam param) {
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            if (selector != null && !selector.isBlank()) {
                // Scroll to a specific element
                page.evaluate(
                    "(selector) => { const el = document.querySelector(selector); if (el) el.scrollIntoView({behavior: 'smooth', block: 'center'}); }",
                    selector);
                return ToolResultBlock.text("Scrolled to element: " + selector);
            } else {
                // Scroll by amount
                Object dxObj = param.getInput().containsKey("scroll_dx") ? param.getInput().get("scroll_dx") : 0;
                Object dyObj = param.getInput().containsKey("scroll_dy") ? param.getInput().get("scroll_dy") : 300;
                double dx = dxObj instanceof Number ? ((Number) dxObj).doubleValue() : 0;
                double dy = dyObj instanceof Number ? ((Number) dyObj).doubleValue() : 300;
                page.evaluate("window.scrollBy(" + dx + ", " + dy + ")");
                return ToolResultBlock.text("Scrolled by (" + dx + ", " + dy + ")");
            }
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to scroll: " + e.getMessage());
        }
    }

    private ToolResultBlock handleBack() {
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            com.microsoft.playwright.Response response = page.goBack();
            String msg = "Navigated back.";
            if (response != null) {
                msg += " Status: " + response.status();
            }
            return ToolResultBlock.text(msg);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to navigate back: " + e.getMessage());
        }
    }

    private ToolResultBlock handleForward() {
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            com.microsoft.playwright.Response response = page.goForward();
            String msg = "Navigated forward.";
            if (response != null) {
                msg += " Status: " + response.status();
            }
            return ToolResultBlock.text(msg);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to navigate forward: " + e.getMessage());
        }
    }

    private ToolResultBlock handleWait(String selector, Double timeout) {
        if (selector == null || selector.isBlank()) {
            return ToolResultBlock.error("selector is required for wait action");
        }
        try {
            com.microsoft.playwright.Page page = browserManager.getPage();
            double timeoutMs = (timeout != null ? timeout : 30.0) * 1000;
            page.waitForSelector(selector, new com.microsoft.playwright.Page.WaitForSelectorOptions()
                .setTimeout(timeoutMs));
            return ToolResultBlock.text("Element appeared: " + selector);
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to wait for '" + selector + "': " + e.getMessage());
        }
    }
}
