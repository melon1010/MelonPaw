package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "token-usage", description = "Read token usage reports", mixinStandardHelpOptions = true,
        subcommands = {TokenUsageCommand.Summary.class, TokenUsageCommand.Details.class})
public class TokenUsageCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    @Option(names = "--start-date") String startDate;
    @Option(names = "--end-date") String endDate;
    @Option(names = "--provider") String provider;
    @Option(names = "--model") String model;
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/token-usage" + query(startDate, endDate, provider, model), null); }

    @Command(name = "summary", mixinStandardHelpOptions = true)
    static class Summary extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--start-date") String startDate;
        @Option(names = "--end-date") String endDate;
        @Option(names = "--provider") String provider;
        @Option(names = "--model") String model;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/token-usage" + query(startDate, endDate, provider, model), null); }
    }

    @Command(name = "details", mixinStandardHelpOptions = true)
    static class Details extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--start-date") String startDate;
        @Option(names = "--end-date") String endDate;
        @Option(names = "--provider") String provider;
        @Option(names = "--model") String model;
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/token-usage/details" + query(startDate, endDate, provider, model), null); }
    }

    private static String query(String startDate, String endDate, String provider, String model) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("start_date", startDate);
        params.put("end_date", endDate);
        params.put("provider", provider);
        params.put("model", model);
        return CliHttpSupport.query(params);
    }
}
