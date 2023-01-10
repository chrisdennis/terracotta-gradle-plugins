package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;

import static org.gradle.api.plugins.JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME;
import static org.terracotta.build.Utils.coordinate;

/**
 * Galvsn based testing plugin.
 * <p>
 * This plugin ensures the test task of the associated project is able to run Galvan tests. That consists of:
 * <ul>
 *   <li>Adding the galvan-support-ee test dependency</li>
 *   <li>Ensuring test test-kit has been built and linking to it properly</li>
 * </ul>
 */
public class GalvanPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);

    Provider<Directory> galvanDir = project.getLayout().getBuildDirectory().dir("galvan");

    project.getConfigurations().getByName(TEST_IMPLEMENTATION_CONFIGURATION_NAME, testImplementation -> {
      Dependency galvanSupport = project.getDependencies().project(coordinate(":multi-stripe:galvan-support-ee"));
      testImplementation.getDependencies().add(galvanSupport);
    });

    Provider<Configuration> kit = project.getConfigurations().register("galvan-kit", config -> {
      config.setCanBeConsumed(false);
      config.setVisible(false);
      config.getDependencies().add(project.getDependencies().project(coordinate(":terracotta", "kit")));
    });

    project.getTasks().named("test", Test.class, task -> {
      task.dependsOn(kit);

      task.setMaxParallelForks(3);

      /*
       * Do **not** convert the anonymous Action here to a lambda expression - it will break Gradle's up-to-date tracking
       * and cause tasks to be needlessly rerun.
       */
      task.doFirst(new Action<Task>() {
        @Override
        public void execute(Task t) {
          task.systemProperty("kitInstallationPath", kit.get().getSingleFile().getAbsolutePath());
        }
      });
      task.systemProperty("kitTestDirectory", galvanDir.get().getAsFile().getAbsolutePath());
      // Use -Pgalvan.noclean to prevent Galvan from discarding test directories
      if (project.hasProperty("galvan.noclean")) {
        task.systemProperty("galvan.noclean", project.property("galvan.noclean"));
      }
    });
  }
}
