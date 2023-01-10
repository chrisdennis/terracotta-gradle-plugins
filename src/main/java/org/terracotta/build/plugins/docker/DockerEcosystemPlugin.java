package org.terracotta.build.plugins.docker;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.provider.Provider;

import java.util.Map;

import static java.util.stream.Collectors.toMap;

public class DockerEcosystemPlugin implements Plugin<Project> {

  public static final String DOCKER_IMAGE_ID = "docker-image";

  @Override
  public void apply(Project project) {
    NamedDomainObjectProvider<Configuration> dockerBucket = project.getConfigurations().register("docker", config -> {
      config.setDescription("Docker image dependencies.");
      config.setCanBeConsumed(false);
      config.setCanBeResolved(false);
      config.setVisible(false);
    });

    NamedDomainObjectProvider<Configuration> dockerImageIds = project.getConfigurations().register("dockerImageIds", config -> {
      config.setDescription("Incoming docker image-id files.");
      config.setCanBeConsumed(false);
      config.setCanBeResolved(true);
      config.setVisible(false);
      config.extendsFrom(dockerBucket.get());
      config.attributes(attrs -> attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, DOCKER_IMAGE_ID)));
    });

    project.getExtensions().create("docker", DockerExtension.class, dockerImageIds);
  }

  public static class DockerExtension {

    private final Images images;

    public DockerExtension(Provider<Configuration> imageIdConfiguration) {
      this.images = new Images(imageIdConfiguration);
    }

    public Images getImages() {
      return images;
    }
  }

  public static class Images {

    private final Provider<Map<String, String>> images;

    public Images(Provider<Configuration> imageIdConfiguration) {
      this.images = imageIdConfiguration.flatMap(c -> c.getElements().map(files -> files.stream().collect(
              toMap(a -> a.getAsFile().getName().replaceAll("\\.iid$", ""), DockerBuild::readImageId))));
    }

    public String getByName(String name) {
      return images.get().get(name);
    }

    public String getAt(String name) {
      return getByName(name);
    }

    public Provider<Map<String, String>> all() {
      return images;
    }
  }
}
