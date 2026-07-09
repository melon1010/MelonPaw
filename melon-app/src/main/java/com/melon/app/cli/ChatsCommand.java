package com.melon.app.cli;

import com.melon.app.cli.spec.AbstractHttpCommand;
import com.melon.app.cli.spec.CliCommandSpec;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "chats", aliases = "chat", description = "Manage chat sessions", mixinStandardHelpOptions = true,
        subcommands = {ChatsCommand.ListChats.class, ChatsCommand.GetChat.class, ChatsCommand.CreateChat.class,
                ChatsCommand.UpdateChat.class, ChatsCommand.DeleteChat.class})
public class ChatsCommand extends AbstractHttpCommand implements Callable<Integer> {
    private static final CliCommandSpec GET = CliCommandSpec.builder("chats.get").get("/api/chats/{id}").build();
    private static final CliCommandSpec CREATE = CliCommandSpec.builder("chats.create").post("/api/chats").build();
    private static final CliCommandSpec UPDATE = CliCommandSpec.builder("chats.update").put("/api/chats/{id}").build();
    private static final CliCommandSpec DELETE = CliCommandSpec.builder("chats.delete").delete("/api/chats/{id}").build();

    @Override
    public Integer call() { return execute(CliCommandSpecs.CHATS_LIST); }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListChats extends AbstractHttpCommand implements Callable<Integer> { public Integer call() { return execute(CliCommandSpecs.CHATS_LIST); } }
    @Command(name = "get", mixinStandardHelpOptions = true)
    static class GetChat extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0") String id;
        public Integer call() { return execute(GET, Map.of("id", id), null); }
    }
    @Command(name = "create", mixinStandardHelpOptions = true)
    static class CreateChat extends AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--name") String name;
        public Integer call() { return execute(CREATE, Map.of(), name == null ? Map.of() : Map.of("name", name)); }
    }
    @Command(name = "update", mixinStandardHelpOptions = true)
    static class UpdateChat extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Option(names = "--name", required = true) String name;
        public Integer call() { return execute(UPDATE, Map.of("id", id), Map.of("name", name)); }
    }
    @Command(name = "delete", mixinStandardHelpOptions = true)
    static class DeleteChat extends AbstractHttpCommand implements Callable<Integer> {
        @Parameters(index = "0") String id;
        public Integer call() { return execute(DELETE, Map.of("id", id), new LinkedHashMap<>()); }
    }
}
