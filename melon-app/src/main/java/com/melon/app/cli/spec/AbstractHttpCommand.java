package com.melon.app.cli.spec;

import com.melon.app.cli.context.CliContext;
import com.melon.app.cli.context.CliOptionResolver;
import com.melon.app.cli.http.CliHttpClient;
import com.melon.app.cli.http.CliHttpException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.Map;

public abstract class AbstractHttpCommand {

    @Spec
    protected CommandSpec commandSpec;

    protected int execute(CliCommandSpec spec) {
        return execute(spec, Map.of(), null);
    }

    protected int execute(CliCommandSpec spec, Map<String, String> pathParams, Object body) {
        return execute(spec, pathParams, body, Map.of());
    }

    protected int execute(CliCommandSpec spec, Map<String, String> pathParams, Object body, Map<String, String> headers) {
        CliContext context = CliContext.from(CliOptionResolver.from(commandSpec));
        CliHttpClient client = new CliHttpClient();
        try {
            return client.printResponse(context, spec, client.execute(context, spec, pathParams, body, headers));
        } catch (CliHttpException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
