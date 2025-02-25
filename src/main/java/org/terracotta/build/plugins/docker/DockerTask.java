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

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Input;
import org.gradle.process.ExecSpec;
import org.gradle.process.internal.ExecException;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

import static java.lang.Long.parseLong;
import static java.lang.Math.toIntExact;
import static java.lang.Thread.sleep;
import static java.util.Collections.emptyMap;
import static org.gradle.internal.Actions.composite;
import static org.terracotta.build.ExecUtils.execQuietlyUnder;
import static org.terracotta.build.ExecUtils.execUnder;
import static org.terracotta.build.PluginUtils.capitalize;

public abstract class DockerTask extends DefaultTask {

  public DockerTask() {
    getDocker().convention("docker");
    getAvailableTimeout().convention(getProject().getProviders().gradleProperty("dockerAvailableTimeout").map(timeout -> Duration.ofMillis(parseLong(timeout))).orElse(Duration.ofMillis(1000)));
  }

  @Input
  public abstract Property<String> getDocker();

  @Input
  public abstract MapProperty<String, String> getDockerEnv();

  public String docker(Action<ExecSpec> action) {
    return execUnder(this, composite(exec -> {
      exec.environment(getDockerEnv().getOrElse(emptyMap()));
      exec.executable(getDocker().get());
    }, action));
  }

  public String dockerQuietly(Action<ExecSpec> action) {
    return execQuietlyUnder(this, composite(exec -> {
      exec.environment(getDockerEnv().getOrElse(emptyMap()));
      exec.executable(getDocker().get());
    }, action));
  }

  @Input
  public abstract Property<Duration> getAvailableTimeout();

  public Spec<? super Task> dockerAvailable() {
    Property<Boolean> dockerAvailableProperty = getProject().getObjects().property(Boolean.class).value(getProject().provider(() -> {
      getDocker().finalizeValue();

      URI dockerHost;
      try {
        String docker = dockerQuietly(spec -> spec.args("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")).trim();
        dockerHost = new URI(docker);
      } catch (ExecException e) {
        return false;
      }

      if ("tcp".equals(dockerHost.getScheme())) {
        InetSocketAddress dockerSocketAddress = new InetSocketAddress(dockerHost.getHost(), dockerHost.getPort());
        try (Socket socket = new Socket()) {
          socket.connect(dockerSocketAddress, toIntExact(getAvailableTimeout().get().toMillis()));
        } catch (SocketTimeoutException | ConnectException timeout) {
          return false;
        }
      }

      try {
        dockerQuietly(spec -> spec.args("info", "--format", "'{{.ServerVersion}}'"));
        return true;
      } catch (ExecException e) {
        return false;
      }
    }));
    dockerAvailableProperty.finalizeValueOnRead();

    return unused -> dockerAvailableProperty.get();
  }

  protected DockerRegistryService getRegistryServiceFor(DockerRegistry registry) {
    return getProject().getGradle().getSharedServices().registerIfAbsent(
            "dockerRegistry" + capitalize(registry.getName()),
            DockerRegistryService.class, serviceSpec -> serviceSpec.parameters(parameters -> {
              parameters.getDocker().set(getDocker());
              parameters.getRegistryUri().set(registry.getUri());
              Property<PasswordCredentials> credentials = registry.getCredentials();
              if (credentials.isPresent()) {
                parameters.getUsername().set(credentials.map(PasswordCredentials::getUsername));
                parameters.getPassword().set(credentials.map(PasswordCredentials::getPassword));
              }
            })).get();
  }

  protected <T, F extends Throwable> T withRetry(String description, DockerRegistry.Retry retry, Class<F> retryable, Retryable<T, F> runnable) throws F {
    int retryAttempts = retry.getAttempts().get();
    Function<Integer, Duration> delay;
    if (retryAttempts > 0) {
      delay = retry.getDelay().get();
    } else {
      delay = i -> { throw new AssertionError(); };
    }

    for (int i = 0; ; i++) {
      try {
        return runnable.execute();
      } catch (Throwable failure) {
        if (retryable.isInstance(failure) && i < retryAttempts) {
          Duration retryDelay = delay.apply(i);
          getLogger().error("{} failed - Retry {}/{} in {}", description, (i + 1), retryAttempts, retryDelay);
          try {
            sleep(retryDelay.toMillis());
          } catch (InterruptedException interrupt) {
            failure.addSuppressed(interrupt);
            throw failure;
          }
        } else {
          throw failure;
        }
      }
    }
  }

  interface Retryable<T, F extends Throwable> {
    T execute() throws F;
  }
}
