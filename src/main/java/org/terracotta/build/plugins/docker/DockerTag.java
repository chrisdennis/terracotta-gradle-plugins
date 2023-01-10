package org.terracotta.build.plugins.docker;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

public abstract class DockerTag extends DockerTask {

  public DockerTag() {
    setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
  }

  @TaskAction
  public void tag() {
    getTags().get().forEach(tag -> docker(spec -> spec.args("tag", getImageId().get(), tag)));
  }

  @Input
  public abstract ListProperty<String> getTags();

  @Input
  public abstract Property<String> getImageId();
}
