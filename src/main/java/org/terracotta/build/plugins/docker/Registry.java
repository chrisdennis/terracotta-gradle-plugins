package org.terracotta.build.plugins.docker;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

public interface Registry extends Named {

  Property<URI> getUri();

  Property<String> getOrganization();

  Property<PasswordCredentials> getCredentials();

  @Nested
  Retry getRetry();

  default void retry(Action<Retry> action) {
    action.execute(getRetry());
  }

  interface Retry {

    Property<Integer> getAttempts();

    Property<Function<Integer, Duration>> getDelay();

    default void delay(Duration delay) {
      getDelay().set(unused -> delay);
    }
  }
}
