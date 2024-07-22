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

package org.terracotta.build.plugins.docker;

import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecSpec;
import org.terracotta.build.ExecUtils;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.util.EnumSet.of;
import static org.gradle.internal.Actions.composite;

public abstract class DockerRegistryService implements BuildService<DockerRegistryService.Parameters>, AutoCloseable {

  private static final Logger LOGGER = Logging.getLogger(DockerRegistryService.class);

  @Inject
  public abstract ExecOperations getExecOperations();

  private Path configDirectory;

  public Action<ExecSpec> login(Action<ExecSpec> action) {
    return spec -> {
      login().ifPresent(config -> spec.args("--config", config.toString()));
      action.execute(spec);
    };
  }

  public void close() {
    logout();
  }

  private synchronized Optional<Path> login() {
    if (configDirectory == null) {
      Provider<String> username = getParameters().getUsername();
      Provider<String> password = getParameters().getPassword();
      Provider<URI> registryUri = getParameters().getRegistryUri();

      if (username.isPresent()) {
        try {
          Path directory = Files.createTempDirectory("docker-config-", PosixFilePermissions.asFileAttribute(of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))).toAbsolutePath();

          ByteArrayInputStream standardIn = new ByteArrayInputStream(password.getOrElse("").getBytes());
          docker(login -> {
            login.args("--config", directory.toString(),
                    "login",
                    "--username", username.get(),
                    "--password-stdin",
                    registryUri.get());

            login.setStandardInput(standardIn);
          });
          return Optional.of(configDirectory = directory);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.of(configDirectory);
    }
  }

  private synchronized void logout() {
    if (configDirectory != null) {
      Provider<URI> registryUri = getParameters().getRegistryUri();
      try {
        docker(logout -> {
          logout.args("--config", configDirectory.toString(),
                  "logout",
                  registryUri.get());
        });
      } finally {
        try {
          org.terracotta.utilities.io.Files.deleteTree(configDirectory);
        } catch (IOException e) {
          LOGGER.warn("Failed to purge private login config directory: {}", configDirectory);
        }
      }

    }
  }

  private void docker(Action<ExecSpec> action) {
    ExecUtils.execute(getExecOperations(), composite(spec -> spec.executable(getParameters().getDocker().get()), action));
  }

  public interface Parameters extends BuildServiceParameters {

    Property<String> getDocker();

    Property<URI> getRegistryUri();

    Property<String> getUsername();

    Property<String> getPassword();
  }
}
