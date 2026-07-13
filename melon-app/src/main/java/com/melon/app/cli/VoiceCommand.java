package com.melon.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "voice", description = "Manage voice transcription", mixinStandardHelpOptions = true,
        subcommands = {VoiceCommand.AudioMode.class, VoiceCommand.Provider.class,
                VoiceCommand.ProviderType.class, VoiceCommand.LocalWhisperStatus.class, VoiceCommand.Transcribe.class})
public class VoiceCommand extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
    public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/audio-mode", null); }

    @Command(name = "audio-mode", mixinStandardHelpOptions = true)
    static class AudioMode extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") String mode;
        public Integer call() {
            return mode == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/workspace/audio-mode", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/audio-mode", Map.of("audio_mode", mode));
        }
    }

    @Command(name = "provider", mixinStandardHelpOptions = true)
    static class Provider extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") List<String> fields;
        public Integer call() {
            return fields == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/workspace/transcription-providers", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/transcription-provider", CliHttpSupport.setBody(fields));
        }
    }

    @Command(name = "provider-type", mixinStandardHelpOptions = true)
    static class ProviderType extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--set") String type;
        public Integer call() {
            return type == null
                    ? CliHttpSupport.request(commandSpec, "GET", "/api/workspace/transcription-provider-type", null)
                    : CliHttpSupport.request(commandSpec, "PUT", "/api/workspace/transcription-provider-type", Map.of("transcription_provider_type", type));
        }
    }

    @Command(name = "local-whisper-status", mixinStandardHelpOptions = true)
    static class LocalWhisperStatus extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        public Integer call() { return CliHttpSupport.request(commandSpec, "GET", "/api/workspace/local-whisper-status", null); }
    }

    @Command(name = "transcribe", mixinStandardHelpOptions = true)
    static class Transcribe extends com.melon.app.cli.spec.AbstractHttpCommand implements Callable<Integer> {
        @Option(names = "--file", required = true) Path file;
        public Integer call() { return CliHttpSupport.multipart(commandSpec, "/api/workspace/transcribe", file, "file", Map.of(), Map.of()); }
    }
}
