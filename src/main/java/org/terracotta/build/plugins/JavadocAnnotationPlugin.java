/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

public class JavadocAnnotationPlugin implements Plugin<Project> {
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
