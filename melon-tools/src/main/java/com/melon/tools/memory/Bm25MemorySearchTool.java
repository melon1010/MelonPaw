package com.melon.tools.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-native BM25 memory search for MEMORY.md and memory/*.md.
 */
public class Bm25MemorySearchTool {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS = 20;
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final WorkspaceManager workspaceManager;

    public Bm25MemorySearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public Bm25MemorySearchTool(Path workspaceDir) {
        this(new WorkspaceManager(workspaceDir));
    }

    @Tool(
            name = "memory_search",
            readOnly = true,
            concurrencySafe = true,
            description =
                    "Search long-term memory files (MEMORY.md and memory/*.md) with BM25 keyword"
                            + " ranking. Use before answering questions about prior work,"
                            + " decisions, dates, people, preferences, or todos.")
    public String memorySearch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(
                            name = "max_results",
                            description = "Maximum number of results to return. Defaults to 5.",
                            required = false)
                    Integer maxResults,
            @ToolParam(
                            name = "min_score",
                            description = "Minimum BM25 score. Defaults to 0.",
                            required = false)
                    Double minScore) {
        if (query == null || query.isBlank()) {
            return "Error: query cannot be empty";
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return "Error: query cannot be empty";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        List<Doc> docs = loadDocs(rc);
        if (docs.isEmpty()) {
            return "(no memory results)";
        }

        Map<String, Integer> df = documentFrequency(docs);
        double avgLen = docs.stream().mapToInt(Doc::length).average().orElse(1.0);
        int limit = Math.min(MAX_RESULTS, Math.max(1, maxResults != null ? maxResults : DEFAULT_MAX_RESULTS));
        double floor = Math.max(0.0, minScore != null ? minScore : 0.0);
        String phrase = query.strip().toLowerCase(Locale.ROOT);

        List<ScoredDoc> scored = new ArrayList<>();
        for (Doc doc : docs) {
            double score = score(doc, queryTerms, df, docs.size(), avgLen);
            if (doc.text().toLowerCase(Locale.ROOT).contains(phrase)) {
                score += 1.0;
            }
            if (score > floor) {
                scored.add(new ScoredDoc(doc, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
        if (scored.isEmpty()) {
            return "(no memory results)";
        }

        StringBuilder out = new StringBuilder("Found ")
                .append(Math.min(limit, scored.size()))
                .append(" relevant memories:\n\n");
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            ScoredDoc hit = scored.get(i);
            Doc doc = hit.doc();
            out.append("Source: ")
                    .append(doc.path())
                    .append("#")
                    .append(doc.line())
                    .append(" (score: ")
                    .append(String.format(Locale.ROOT, "%.4f", hit.score()))
                    .append(")\n")
                    .append(doc.text())
                    .append("\n\n");
        }
        return out.toString().stripTrailing();
    }

    private List<Doc> loadDocs(RuntimeContext rc) {
        List<Doc> docs = new ArrayList<>();
        for (String path : workspaceManager.listMemoryFilePaths(rc)) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, path);
            if (content == null || content.isBlank()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String text = lines[i].strip();
                if (!text.isEmpty()) {
                    docs.add(Doc.of(path, i + 1, text));
                }
            }
        }
        return docs;
    }

    private static Map<String, Integer> documentFrequency(List<Doc> docs) {
        Map<String, Integer> df = new HashMap<>();
        for (Doc doc : docs) {
            doc.tf().keySet().forEach(term -> df.merge(term, 1, Integer::sum));
        }
        return df;
    }

    private static double score(Doc doc, List<String> queryTerms, Map<String, Integer> df, int totalDocs, double avgLen) {
        double score = 0.0;
        for (String term : queryTerms) {
            int tf = doc.tf().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int docFreq = df.getOrDefault(term, 0);
            double idf = Math.log(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));
            double norm = tf + K1 * (1.0 - B + B * doc.length() / Math.max(1.0, avgLen));
            score += idf * (tf * (K1 + 1.0)) / norm;
        }
        return score;
    }

    private static List<String> tokenize(String text) {
        Map<String, Boolean> terms = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            addToken(terms, matcher.group());
        }
        return new ArrayList<>(terms.keySet());
    }

    private static void addToken(Map<String, Boolean> terms, String raw) {
        String token = raw.toLowerCase(Locale.ROOT);
        if (!token.isBlank()) {
            terms.put(token, Boolean.TRUE);
        }
        for (String part : raw.replace('_', ' ').split("\\s+")) {
            for (String camel : part.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").split("\\s+")) {
                String value = camel.toLowerCase(Locale.ROOT);
                if (!value.isBlank()) {
                    terms.put(value, Boolean.TRUE);
                }
            }
        }
        raw.codePoints()
                .filter(Bm25MemorySearchTool::isCjk)
                .mapToObj(cp -> new String(Character.toChars(cp)))
                .forEach(ch -> terms.put(ch, Boolean.TRUE));
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private record Doc(String path, int line, String text, Map<String, Integer> tf, int length) {
        static Doc of(String path, int line, String text) {
            Map<String, Integer> tf = new HashMap<>();
            for (String term : tokenize(text)) {
                tf.merge(term, 1, Integer::sum);
            }
            int length = tf.values().stream().mapToInt(Integer::intValue).sum();
            return new Doc(path, line, text, tf, Math.max(1, length));
        }
    }

    private record ScoredDoc(Doc doc, double score) {
    }
}
