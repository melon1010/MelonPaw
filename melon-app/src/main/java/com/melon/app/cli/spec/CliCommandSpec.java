package com.melon.app.cli.spec;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class CliCommandSpec {

    private final String name;
    private final String method;
    private final String path;
    private final String description;
    private final Set<Integer> successStatuses;

    private CliCommandSpec(Builder builder) {
        this.name = builder.name;
        this.method = builder.method;
        this.path = builder.path;
        this.description = builder.description;
        this.successStatuses = Set.copyOf(builder.successStatuses);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String description() {
        return description;
    }

    public Set<Integer> successStatuses() {
        return successStatuses;
    }

    public String expandPath(Map<String, String> pathParams) {
        String expanded = path;
        if (pathParams != null) {
            for (var entry : pathParams.entrySet()) {
                expanded = expanded.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return expanded;
    }

    public static final class Builder {
        private final String name;
        private String method = "GET";
        private String path = "/";
        private String description = "";
        private final Set<Integer> successStatuses = new LinkedHashSet<>(Set.of(200));

        private Builder(String name) {
            this.name = name;
        }

        public Builder get(String path) {
            this.method = "GET";
            this.path = path;
            return this;
        }

        public Builder post(String path) {
            this.method = "POST";
            this.path = path;
            this.successStatuses.add(201);
            this.successStatuses.add(202);
            return this;
        }

        public Builder put(String path) {
            this.method = "PUT";
            this.path = path;
            return this;
        }

        public Builder patch(String path) {
            this.method = "PATCH";
            this.path = path;
            return this;
        }

        public Builder delete(String path) {
            this.method = "DELETE";
            this.path = path;
            this.successStatuses.add(204);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder successStatuses(Integer... statuses) {
            this.successStatuses.clear();
            this.successStatuses.addAll(Set.of(statuses));
            return this;
        }

        public CliCommandSpec build() {
            return new CliCommandSpec(this);
        }
    }
}
