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

/**
 * Docker '{@code docker build}' task.
 */
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

  /**
   * Dockerfile to build
   *
   * @return target Dockerfile
   */
  @InputFile
  public abstract RegularFileProperty getDockerfile();

  /**
   * Assembled environment for the Dockerfile
   *
   * @return dockerfile environment
   */
  @InputDirectory @SkipWhenEmpty
  public abstract DirectoryProperty getEnvironment();

  /**
   * Image ID file where the resultant image hash will be written
   *
   * @return output image id file
   */
  @OutputFile
  public abstract RegularFileProperty getImageIdFile();

  /**
   * Metadata labels to apply to the image.
   *
   * @return image metadata labels
   */
  @Input
  public abstract MapProperty<String, String> getMetadata();

  /**
   * Map of input build arguments
   *
   * @return build arguments
   */
  @Input
  public abstract MapProperty<String, String> getBuildArgs();

  /**
   * Reusltant image hash/id.
   *
   * @return output image hash
   */
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
