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

/**
 * Abstract super class for all docker command invoking tasks.
 */
public abstract class DockerTask extends DefaultTask {

  public DockerTask() {
    getDocker().convention("docker");
    getAvailableTimeout().convention(getProject().getProviders().gradleProperty("dockerAvailableTimeout").map(timeout -> Duration.ofMillis(parseLong(timeout))).orElse(Duration.ofMillis(1000)));
  }

  /**
   * The docker command, convention defaults this to {@code docker}
   *
   * @return the docker command
   */
  @Input
  public abstract Property<String> getDocker();

  /**
   * The docker execution environment variables.
   *
   * @return the docker command environment
   */
  @Input
  public abstract MapProperty<String, String> getDockerEnv();

  /**
   * Execute docker using the provided action to configure the command.
   * <p>
   * Command output is logged at info, and relogged at error on command failure.
   *
   * @param action execution configuration action
   * @return the standard output of the command
   */
  public String docker(Action<ExecSpec> action) {
    return execUnder(this, composite(exec -> {
      exec.environment(getDockerEnv().getOrElse(emptyMap()));
      exec.executable(getDocker().get());
    }, action));
  }

  /**
   * Execute docker quietly using the provided action to configure the command.
   * <p>
   * Command output is logged at debug regardless of the result of the command.
   * This method is useful for commands where failure is 'normal'.
   *
   * @param action execution configuration action
   * @return the standard output of the command
   */
  public String dockerQuietly(Action<ExecSpec> action) {
    return execQuietlyUnder(this, composite(exec -> {
      exec.environment(getDockerEnv().getOrElse(emptyMap()));
      exec.executable(getDocker().get());
    }, action));
  }

  /**
   * Connection timeout when checking the availability of remote docker servers.
   *
   * @return remote docker availability timeout
   */
  @Input
  public abstract Property<Duration> getAvailableTimeout();

  /**
   * Spec for docker 'availability' (as configured in this task).
   * <p>
   * This method is useful when commands are optional based on the availability of docker.
   *
   * @return docker available {@code Spec}
   */
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
        dockerQuietly(spec -> spec.args("info"));
        return true;
      } catch (ExecException e) {
        return false;
      }
    }));
    dockerAvailableProperty.finalizeValueOnRead();

    return unused -> dockerAvailableProperty.get();
  }

  /**
   * Registry service used to manage login to and eventual logout from a Docker registry.
   *
   * @param registry docker registry details
   * @return registry service for session management
   */
  protected DockerRegistryService getRegistryServiceFor(Registry registry) {
    return getProject().getGradle().getSharedServices().registerIfAbsent(
            "dockerRegistry" + capitalize(registry.getName()),
            DockerRegistryService.class, serviceSpec -> serviceSpec.parameters(parameters -> {
              parameters.getDocker().set(getDocker());
              parameters.getRegistryUri().set(registry.getUri());
              Property<PasswordCredentials> credentials = registry.getCredentials();
              parameters.getUsername().set(credentials.map(PasswordCredentials::getUsername));
              parameters.getPassword().set(credentials.map(PasswordCredentials::getPassword));
            })).get();
  }

  /**
   * Retry a task that can fail based on the provided configuration.
   *
   * @param description task description for logging purposes
   * @param retry retry options
   * @param retryable failure type to trigger retry
   * @param runnable task to retry
   * @return result of the final successful execution
   * @param <T> result type
   * @param <F> retry failure type
   * @throws F on failure through all retries
   */
  protected <T, F extends Throwable> T withRetry(String description, Registry.Retry retry, Class<F> retryable, Retryable<T, F> runnable) throws F {
    int retryAttempts = retry.getAttempts().get();
    Function<Integer, Duration> delay = retry.getDelay().get();
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

  /**
   * A retryable, failable task.
   *
   * @param <T> task result type
   * @param <F> task failure type
   */
  public interface Retryable<T, F extends Throwable> {
    T execute() throws F;
  }
}
