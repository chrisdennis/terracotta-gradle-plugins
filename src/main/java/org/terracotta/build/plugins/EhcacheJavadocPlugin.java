package org.terracotta.build.plugins;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.javadoc.Javadoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.util.regex.Pattern.quote;

public class EhcacheJavadocPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().withType(Javadoc.class).configureEach(javadoc -> {
            javadoc.exclude(fte -> !isPublicApi(fte.getFile().toPath()));
        });
    }

    private static boolean isPublicApi(Path source) {
        if (Files.isDirectory(source)) {
            return true;
        } else {
            return (isTypeAnnotated(source.getParent(), "PublicApi") && !isTypeAnnotated(source, "PrivateApi")) || isTypeAnnotated(source, "PublicApi");
        }
    }

    private static boolean isTypeAnnotated(Path source, String annotation) {
        if (Files.isDirectory(source)) {
            return isTypeAnnotated(source.resolve("package-info.java"), annotation);
        } else if (Files.isRegularFile(source) && source.getFileName().toString().endsWith(".java")) {
            try (Stream<String> lines = Files.lines(source, StandardCharsets.UTF_8)) {
                return lines.anyMatch(line -> line.matches("(?:^|^.*\\s+)@" + quote(annotation) + "(?:$|\\s+.*$)"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            return false;
        }
    }
}
