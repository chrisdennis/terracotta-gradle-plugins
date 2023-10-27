package org.terracotta.build.plugins.docker;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.attributes.Category;
import org.gradle.api.provider.Provider;

import java.util.Map;

import static java.util.stream.Collectors.toMap;

@SuppressWarnings("UnstableApiUsage")
public class DockerEcosystemPlugin implements Plugin<Project> {

  public static final String DOCKER_IMAGE_ID = "docker-image";

  @Override
  public void apply(Project project) {
    /*
     * Dependency scope for docker image dependencies.
     */
    NamedDomainObjectProvider<DependencyScopeConfiguration> dockerBucket = project.getConfigurations().dependencyScope("docker", c -> {
      c.setDescription("Docker image dependencies.");
    });

    /*
     * Resolvable configuration that resolves docker image dependencies to a set of files containing Docker images hashes.
     * We define a new {@code Category} attribute value to distinguish this configuration.
     */
    NamedDomainObjectProvider<ResolvableConfiguration> dockerImageIds = project.getConfigurations().resolvable("dockerImageIds", c -> c
        .setDescription("Incoming docker image-id files.")
        .extendsFrom(dockerBucket.get())
        .attributes(attrs -> attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, DOCKER_IMAGE_ID)))
    );

    /*
     * Register docker extension to house convenience accessor for image ids.
     */
    project.getExtensions().create("docker", DockerExtension.class, dockerImageIds);
  }

  public static class DockerExtension {

    private final Images images;

    public DockerExtension(Provider<ResolvableConfiguration> imageIdConfiguration) {
      this.images = new Images(imageIdConfiguration);
    }

    public Images getImages() {
      return images;
    }
  }

  public static class Images {

    private final Provider<Map<String, String>> images;

    public Images(Provider<ResolvableConfiguration> imageIdConfiguration) {
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
