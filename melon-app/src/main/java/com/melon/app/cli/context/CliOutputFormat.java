package com.melon.app.cli.context;

import picocli.CommandLine.ITypeConverter;

import java.util.Locale;

public enum CliOutputFormat {
    PLAIN,
    JSON,
    TABLE;

    public static class Converter implements ITypeConverter<CliOutputFormat> {
        @Override
        public CliOutputFormat convert(String value) {
            if (value == null || value.isBlank()) {
                return PLAIN;
            }
            return CliOutputFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }
}
