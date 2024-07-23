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
