/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
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

package org.terracotta.build.plugins.docker;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.process.internal.ExecException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Collections.emptyMap;
import static java.util.stream.Stream.of;

public abstract class DockerBuild extends DockerTask {

  public DockerBuild() {
    setGroup(LifecycleBasePlugin.BUILD_GROUP);
    getOutputs().upToDateWhen(task -> getImageIdFile().getLocationOnly().map(iidFile -> {
      if (iidFile.getAsFile().exists()) {
        try {
          dockerQuietly(spec -> {
            spec.args("inspect", "--type", "image", readImageId(iidFile));
          });
          return true;
        } catch (ExecException e) {
          return false;
        }
      } else {
        return false;
      }
    }).getOrElse(false));
  }

  @TaskAction
  public void build() {
    docker(spec -> {
      spec.workingDir(getEnvironment());
      spec.args("build",
              "--file", getDockerfile().get().getAsFile().getAbsolutePath(),
              "--iidfile", getImageIdFile().get().getAsFile().getAbsolutePath());
      spec.args(getMetadata().get().entrySet().stream()
              .flatMap(e -> of("--label", e.getKey() + "=" + e.getValue()))
              .toArray());
      spec.args(getBuildArgs().getOrElse(emptyMap()).entrySet().stream()
          .flatMap(e -> of("--build-arg", e.getKey() + "=" + e.getValue()))
          .toArray());
      spec.args(".");
    });
  }

  @InputFile
  public abstract RegularFileProperty getDockerfile();

  @InputDirectory @SkipWhenEmpty
  public abstract DirectoryProperty getEnvironment();

  @OutputFile
  public abstract RegularFileProperty getImageIdFile();

  @Input
  public abstract MapProperty<String, String> getMetadata();

  @Input
  public abstract MapProperty<String, String> getBuildArgs();

  @Internal
  public Provider<String> getImageId() {
    return getImageIdFile().map(DockerBuild::readImageId);
  }

  protected static String readImageId(FileSystemLocation file) {
    Path path = file.getAsFile().toPath();
    try {
      return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString().trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
