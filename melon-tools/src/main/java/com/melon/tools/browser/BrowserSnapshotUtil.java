package com.melon.tools.browser;

import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * Utility for building ARIA role-based snapshots of a web page.
 * The snapshot represents the page's accessibility tree, which can be used
 * by agents to understand page structure and locate interactive elements.
 */
public final class BrowserSnapshotUtil {

    private static final int MAX_DEPTH = 10;
    private static final int MAX_ELEMENTS = 500;

    private BrowserSnapshotUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * JavaScript that builds an accessibility tree from the DOM.
     * Returns a Map with role, name, value, description, state attributes,
     * and children - matching the structure expected by buildSnapshotRecursive.
     */
    private static final String SNAPSHOT_JS = """
(() => {
  function getRole(el) {
    var explicit = el.getAttribute('role');
    if (explicit) return explicit;
    var tag = el.tagName.toLowerCase();
    if (tag === 'a') return el.hasAttribute('href') ? 'link' : null;
    if (tag === 'button') return 'button';
    if (tag === 'input') {
      var type = (el.getAttribute('type') || 'text').toLowerCase();
      if (type === 'checkbox') return 'checkbox';
      if (type === 'radio') return 'radio';
      if (type === 'button' || type === 'submit' || type === 'reset' || type === 'image') return 'button';
      if (type === 'range') return 'slider';
      if (type === 'number') return 'spinbutton';
      if (type === 'search') return 'searchbox';
      return 'textbox';
    }
    if (tag === 'select') return 'listbox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'img') return 'image';
    if (tag === 'table') return 'table';
    if (tag === 'nav') return 'navigation';
    if (tag === 'main') return 'main';
    if (tag === 'header') return 'banner';
    if (tag === 'footer') return 'contentinfo';
    if (tag === 'aside') return 'complementary';
    if (tag === 'article') return 'article';
    if (tag === 'section') return 'region';
    if (tag === 'form') return 'form';
    if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4' || tag === 'h5' || tag === 'h6') return 'heading';
    if (tag === 'ul' || tag === 'ol') return 'list';
    if (tag === 'li') return 'listitem';
    if (tag === 'dialog') return 'dialog';
    if (tag === 'details') return 'group';
    if (tag === 'summary') return 'button';
    if (tag === 'figure') return 'figure';
    if (tag === 'figcaption') return 'caption';
    if (tag === 'blockquote') return 'blockquote';
    if (tag === 'q') return 'blockquote';
    return null;
  }

  function getName(el) {
    var ariaLabel = el.getAttribute('aria-label');
    if (ariaLabel) return ariaLabel;
    var alt = el.getAttribute('alt');
    if (alt) return alt;
    var title = el.getAttribute('title');
    if (title) return title;
    var placeholder = el.getAttribute('placeholder');
    if (placeholder) return placeholder;
    var text = (el.textContent || '').trim();
    return text.length > 0 ? text.substring(0, 80) : null;
  }

  function buildNode(el, depth) {
    if (!el || depth > 10) return null;
    var role = getRole(el);
    var name = getName(el);
    if (!role && !name) return null;

    var node = {};
    if (role) node.role = role;
    if (name) node.name = name;

    var val = el.getAttribute('aria-valuenow') || el.getAttribute('value');
    if (val) node.value = val;

    if (el.getAttribute('aria-disabled') === 'true' || el.disabled) node.disabled = true;
    if (el.getAttribute('aria-checked')) node.checked = el.getAttribute('aria-checked') === 'true';
    if (el.getAttribute('aria-expanded')) node.expanded = el.getAttribute('aria-expanded') === 'true';
    if (el.getAttribute('aria-pressed')) node.pressed = el.getAttribute('aria-pressed') === 'true';
    if (el.getAttribute('aria-selected')) node.selected = el.getAttribute('aria-selected') === 'true';
    if (el.getAttribute('aria-readonly') === 'true' || el.readOnly) node.readonly = true;
    if (el.getAttribute('aria-required') === 'true' || el.required) node.required = true;
    if (el.getAttribute('aria-invalid') === 'true') node.invalid = true;
    if (el.getAttribute('aria-modal') === 'true') node.modal = true;

    var tag = el.tagName.toLowerCase();
    if (tag === 'h1') node.level = 1;
    else if (tag === 'h2') node.level = 2;
    else if (tag === 'h3') node.level = 3;
    else if (tag === 'h4') node.level = 4;
    else if (tag === 'h5') node.level = 5;
    else if (tag === 'h6') node.level = 6;
    var ariaLevel = el.getAttribute('aria-level');
    if (ariaLevel && !node.level) node.level = parseInt(ariaLevel);

    var children = [];
    var childNodes = el.children;
    for (var i = 0; i < childNodes.length; i++) {
      var childNode = buildNode(childNodes[i], depth + 1);
      if (childNode) children.push(childNode);
    }
    if (children.length > 0) node.children = children;

    return node;
  }

  return buildNode(document.body, 0);
})()
""";

    /**
     * Builds an ARIA role-based snapshot of the given page.
     * The snapshot is a text representation of the accessibility tree,
     * showing element roles, names, and states in a hierarchical format.
     *
     * @param page the Playwright Page to snapshot
     * @return a text representation of the page's ARIA accessibility tree
     */
    @SuppressWarnings("unchecked")
    public static String buildSnapshot(Page page) {
        StringBuilder sb = new StringBuilder();
        try {
            // Use JavaScript evaluation to build the accessibility tree
            // (page.accessibility() was removed in Playwright 1.45.0)
            Map<String, Object> rootSnapshot = (Map<String, Object>) page.evaluate(SNAPSHOT_JS);
            if (rootSnapshot == null) {
                return "Empty page - no accessibility tree available.";
            }

            int[] count = {0};
            buildSnapshotRecursive(rootSnapshot, 0, sb, count);

            if (count[0] >= MAX_ELEMENTS) {
                sb.append("\n... [snapshot truncated at ").append(MAX_ELEMENTS).append(" elements]");
            }
        } catch (Exception e) {
            sb.append("Error building snapshot: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Recursively traverses the accessibility tree and formats each node.
     *
     * @param node  the accessibility node (Map with role, name, children, etc.)
     * @param depth the current depth in the tree
     * @param sb    the StringBuilder to append to
     * @param count the element counter (array for mutable int)
     */
    @SuppressWarnings("unchecked")
    private static void buildSnapshotRecursive(Map<String, Object> node, int depth,
                                                StringBuilder sb, int[] count) {
        if (node == null || depth > MAX_DEPTH || count[0] >= MAX_ELEMENTS) {
            return;
        }

        String role = getStringValue(node, "role");
        String name = getStringValue(node, "name");
        String value = getStringValue(node, "value");
        String description = getStringValue(node, "description");

        // Build state string from various boolean/string state attributes
        String state = buildStateString(node);

        // Format: indentation + [role] name (state)
        String indent = "  ".repeat(depth);
        sb.append(indent);
        sb.append("[").append(role != null ? role : "unknown").append("]");

        if (name != null && !name.isEmpty()) {
            sb.append(" ").append(name);
        }
        if (value != null && !value.isEmpty()) {
            sb.append("=\"").append(value).append("\"");
        }
        if (description != null && !description.isEmpty()) {
            sb.append(" {").append(description).append("}");
        }
        if (!state.isEmpty()) {
            sb.append(" (").append(state).append(")");
        }
        sb.append("\n");

        count[0]++;

        // Recurse into children
        Object childrenObj = node.get("children");
        if (childrenObj instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    buildSnapshotRecursive((Map<String, Object>) childMap, depth + 1, sb, count);
                    if (count[0] >= MAX_ELEMENTS) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Builds a comma-separated state string from ARIA state attributes.
     */
    private static String buildStateString(Map<String, Object> node) {
        StringBuilder state = new StringBuilder();

        appendStateIfPresent(node, "disabled", state);
        appendStateIfPresent(node, "checked", state);
        appendStateIfPresent(node, "expanded", state);
        appendStateIfPresent(node, "pressed", state);
        appendStateIfPresent(node, "selected", state);
        appendStateIfPresent(node, "readonly", state);
        appendStateIfPresent(node, "required", state);
        appendStateIfPresent(node, "focused", state);
        appendStateIfPresent(node, "modal", state);
        appendStateIfPresent(node, "multiselectable", state);
        appendStateIfPresent(node, "invalid", state);

        // Level for headings, treeitems, etc.
        Object level = node.get("level");
        if (level instanceof Number num && num.intValue() > 0) {
            if (state.length() > 0) state.append(",");
            state.append("level=").append(num.intValue());
        }

        String result = state.toString();
        return result.endsWith(",") ? result.substring(0, result.length() - 1) : result;
    }

    /**
     * Appends a state attribute to the state string if it's truthy.
     */
    private static void appendStateIfPresent(Map<String, Object> node, String key, StringBuilder state) {
        Object val = node.get(key);
        if (val == null) return;

        boolean truthy = false;
        if (val instanceof Boolean b) {
            truthy = b;
        } else if (val instanceof String s) {
            truthy = "true".equalsIgnoreCase(s);
        }

        if (truthy) {
            if (state.length() > 0) state.append(",");
            state.append(key);
        }
    }

    /**
     * Safely gets a string value from the accessibility node.
     */
    private static String getStringValue(Map<String, Object> node, String key) {
        Object val = node.get(key);
        if (val == null) return null;
        String str = val.toString().trim();
        return str.isEmpty() ? null : str;
    }
}
