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

/**
 * Docker '{@code docker push}' task.
 */
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
      Registry registry = getRegistry().get();
      DockerRegistryService registryService = getRegistryServiceFor(registry);

      getTags().get().forEach(tag -> {
        withRetry("Push of " + tag, registry.getRetry(), ExecException.class,
                () -> docker(registryService.login(spec -> spec.args("push", tag))));
      });
    }
  }

  /**
   * Docker registry to push images to.
   *
   * @return target docker registry
   */
  @Internal
  public abstract Property<Registry> getRegistry();

  /**
   * List of docker tags to push.
   *
   * @return tags to push
   */
  @Input
  public abstract ListProperty<String> getTags();
}
