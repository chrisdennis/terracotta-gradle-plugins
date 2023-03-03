package org.terracotta.build.plugins.docker;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

public abstract class DockerBuildExtension {

  private final CopySpec contents;

  public DockerBuildExtension(Project project) {
    this.contents = project.copySpec();
  }

  public CopySpec getContents() {
    return contents;
  }

  public void contents(Action<CopySpec> action) {
    action.execute(getContents());
  }

  public abstract NamedDomainObjectContainer<Registry> getRegistries();

  public abstract RegularFileProperty getDockerFile();

  public abstract DirectoryProperty getContentsDirectory();

  public abstract Property<String> getImageName();

  public abstract ListProperty<String> getTags();

  public abstract MapProperty<String, String> getMetadata();

  public abstract RegularFileProperty getDockerReadme();

  public abstract DirectoryProperty getDocTemplates();
  public abstract MapProperty<String, Object> getDocMetadata();

  public abstract DirectoryProperty getSagDocDirectory();
}
