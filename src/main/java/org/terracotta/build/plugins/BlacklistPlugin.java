package org.terracotta.build.plugins;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.MutableVersionConstraint;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.tasks.SourceSetContainer;

import java.util.Locale;

import static org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME;

public class BlacklistPlugin implements Plugin<Project> {

  private static final String BLACKLIST_CONFIGURATION = "blacklist";
  private static final String TEST_BLACKLIST_CONFIGURATION = "testBlacklist";

  @Override
  public void apply(Project project) {
    NamedDomainObjectProvider<Configuration> blacklist = project.getConfigurations().register(BLACKLIST_CONFIGURATION, config -> {
      config.setVisible(false);
      config.setCanBeConsumed(false);
      config.setCanBeResolved(false);
    });

    project.getConfigurations().configureEach(other -> {
      Configuration config = blacklist.get();
      if (other != config) {
        other.extendsFrom(config);
      }
    });

    NamedDomainObjectProvider<Configuration> testBlacklist = project.getConfigurations().register(TEST_BLACKLIST_CONFIGURATION, config -> {
      config.setVisible(false);
      config.setCanBeConsumed(false);
      config.setCanBeResolved(false);
    });

    project.getPlugins().withType(JavaBasePlugin.class).configureEach(javaBasePlugin -> {
      project.getExtensions().configure(SourceSetContainer.class, sourceSets ->
              sourceSets.matching(set -> set.getName().toLowerCase(Locale.ROOT).contains(TEST_SOURCE_SET_NAME))
                      .configureEach(set -> project.getConfigurations().named(set.getRuntimeClasspathConfigurationName(),
                              configuration -> configuration.extendsFrom(testBlacklist.get()))));
    });

    project.getExtensions().create(BlacklistExtension.class, "blacklist", BlacklistExtension.class, project);
  }

  public static class BlacklistExtension {

    private Project project;

    public BlacklistExtension(Project project) {
      this.project = project;
    }

    public void all(Object dependencyNotation) {
      project.getDependencies().getConstraints().add(BLACKLIST_CONFIGURATION, dependencyNotation,
              constraint -> constraint.version(MutableVersionConstraint::rejectAll));
    }

    public void test(Object dependencyNotation) {
      project.getDependencies().getConstraints().add(TEST_BLACKLIST_CONFIGURATION, dependencyNotation,
              constraint -> constraint.version(MutableVersionConstraint::rejectAll));
    }

  }
}
