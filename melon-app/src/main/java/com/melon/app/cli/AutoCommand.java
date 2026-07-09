package com.melon.app.cli;

import com.melon.app.cli.spec.CliCommandSpec;
import com.melon.app.cli.spec.CliCommandSpecs;
import picocli.CommandLine.Command;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.concurrent.Callable;

@Command(name = "auto", description = "List registered HTTP CLI specs", mixinStandardHelpOptions = true)
public class AutoCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        java.util.Arrays.stream(CliCommandSpecs.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType().equals(CliCommandSpec.class))
                .sorted(Comparator.comparing(Field::getName))
                .forEach(this::print);
        return 0;
    }

    private void print(Field field) {
        try {
            CliCommandSpec spec = (CliCommandSpec) field.get(null);
            System.out.printf("%s\t%s\t%s%n", spec.name(), spec.method(), spec.path());
        } catch (IllegalAccessException ignored) {
        }
    }
}
