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

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.internal.ExecException;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

import static org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP;

public abstract class DockerPush extends DockerTask {

  public DockerPush() {
    setGroup(PUBLISH_TASK_GROUP);
    getInputs().property("registryUri", (Callable<URI>) getRegistry().flatMap(Registry::getUri)::get);
    getInputs().property("registryOrganization", (Callable<String>) getRegistry().flatMap(Registry::getOrganization)::get);
  }

  @TaskAction
  public void push() {
    List<String> tags = getTags().get();
    if (!tags.isEmpty()) {
      DockerRegistry registry = getRegistry().get();
      DockerRegistryService registryService = getRegistryServiceFor(registry);

      getTags().get().forEach(tag -> {
        withRetry("Push of " + tag, registry.getRetry(), ExecException.class,
                () -> docker(registryService.login(spec -> spec.args("push", tag))));
      });
    }
  }

  @Internal
  public abstract Property<DockerRegistry> getRegistry();

  @Input
  public abstract ListProperty<String> getTags();
}
