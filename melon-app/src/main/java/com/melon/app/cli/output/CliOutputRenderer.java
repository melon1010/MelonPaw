package com.melon.app.cli.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.cli.context.CliOutputFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CliOutputRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    public String render(Object value, CliOutputFormat format) {
        if (format == CliOutputFormat.JSON) {
            return json(value);
        }
        if (value instanceof String text) {
            return text;
        }
        return json(value);
    }

    public String json(Object value) {
        try {
            Object parsed = value;
            if (value instanceof String text) {
                parsed = mapper.readValue(text, Object.class);
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public String plain(String value) {
        return value == null ? "" : value;
    }

    public String table(List<Map<String, ?>> rows, List<String> columns) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<Integer> widths = new ArrayList<>();
        for (String column : columns) {
            int width = column.length();
            for (Map<String, ?> row : rows) {
                Object value = row.get(column);
                width = Math.max(width, String.valueOf(value == null ? "" : value).length());
            }
            widths.add(width);
        }
        StringBuilder out = new StringBuilder();
        appendRow(out, columns, widths);
        List<String> separators = widths.stream().map("-"::repeat).toList();
        appendRow(out, separators, widths);
        for (Map<String, ?> row : rows) {
            List<String> values = columns.stream()
                    .map(column -> String.valueOf(row.get(column) == null ? "" : row.get(column)))
                    .toList();
            appendRow(out, values, widths);
        }
        return out.toString().stripTrailing();
    }

    private void appendRow(StringBuilder out, List<String> values, List<Integer> widths) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append("  ");
            }
            out.append(padRight(values.get(i), widths.get(i)));
        }
        out.append(System.lineSeparator());
    }

    private String padRight(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }
}
