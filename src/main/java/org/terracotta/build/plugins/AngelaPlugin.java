package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.testing.Test;

import java.nio.file.Files;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.gradle.api.plugins.JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME;
import static org.terracotta.build.Utils.coordinate;
import static org.terracotta.build.Utils.group;

/**
 * Angela based testing plugin.
 * <p>
 * This plugin ensures the test task of the associated project is able to run Angela tests. That consists of:
 * <ul>
 *   <li>Adding the necessary test dependencies: angela, and the kit-resolver</li>
 *   <li>Sourcing a suitable kit build, either the central test-kit, or copying and amending it if extra
 *   {@code pluginLibs} are configured.</li>
 * </ul>
 */
public class AngelaPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);

    Provider<Directory> angelaDir = project.getLayout().getBuildDirectory().dir("angela");
    Provider<Directory> customKitDir = angelaDir.map(d -> d.dir("custom-tc-db-kit"));

    project.getConfigurations().getByName(TEST_IMPLEMENTATION_CONFIGURATION_NAME, testImplementation -> {
      Dependency angela = project.getDependencies().create("org.terracotta:angela:" + project.property("angelaVersion"));
      if (angela instanceof ModuleDependency) {
        ((ModuleDependency) angela).exclude(group("org.slf4j"));
        ((ModuleDependency) angela).exclude(group("javax.cache"));
      } else {
        throw new IllegalArgumentException();
      }
      Dependency angelaResolver = project.getDependencies().project(coordinate(":common:angela-test-kit-resolver"));
      if (angelaResolver instanceof ModuleDependency) {
        ((ModuleDependency) angelaResolver).exclude(group("org.slf4j"));
        ((ModuleDependency) angelaResolver).exclude(group("javax.cache"));
      } else {
        throw new IllegalArgumentException();
      }

      testImplementation.getDependencies().add(angela);
      testImplementation.getDependencies().add(angelaResolver);
    });

    Provider<Configuration> kit = project.getConfigurations().register("angela-kit", config -> {
      config.setCanBeConsumed(false);
      config.setVisible(false);
      config.getDependencies().add(project.getDependencies().project(coordinate(":terracotta", "kit")));
    });
    Provider<Configuration> pluginLibs = project.getConfigurations().register("pluginLibs", config -> config.setCanBeConsumed(false));
    Provider<Sync> customKitPreparation = project.getTasks().register("angela-custom-kit-preparation", Sync.class, task -> {
      task.onlyIf(t -> !pluginLibs.get().isEmpty());

      task.from(kit);
      task.into(customKitDir);

      Predicate<String> pruning = Stream.of("server/plugins/api", "server/lib")
              .map(dir -> task.getDestinationDir().toPath().resolve(dir))
              .map(dir -> (Predicate<String>) (String file) -> Files.exists(dir.resolve(file)))
              .reduce(Predicate::or).orElse(f -> false);

      task.from(pluginLibs, spec -> {
        spec.into("server/plugins/lib");
        spec.eachFile(fcd -> {
          if (pruning.test(fcd.getName())) {
            fcd.exclude();
          }
        });
      });
    });
    project.getTasks().named("test", Test.class, task -> {
      task.dependsOn(customKitPreparation);

      // concurrently run angela tests
      task.setMaxParallelForks(3);

      /*
       * Angela properties
       */

       /*
       * Do **not** convert the anonymous Action here to a lambda expression - it will break Gradle's up-to-date tracking
       * and cause tasks to be needlessly rerun.
       */
      //noinspection Convert2Lambda
      task.doFirst(new Action<Task>() {
        @Override
        public void execute(Task t) {
          if (pluginLibs.get().isEmpty()) {
            task.systemProperty("angela.kitInstallationDir", kit.get().getSingleFile().getAbsolutePath());
          } else {
            task.systemProperty("angela.kitInstallationDir", customKitDir.get().getAsFile().getAbsolutePath());
          }
        }
      });
      task.systemProperty("IGNITE_UPDATE_NOTIFIER", "false");
      task.systemProperty("angela.rootDir", angelaDir.get().getAsFile().getAbsolutePath());
      task.systemProperty("angela.skipUninstall", "true");
      task.systemProperty("angela.tsa.fullLogging", "true");
      task.systemProperty("angela.igniteLogging", "false");
      task.systemProperty("angela.java.resolver", "user"); // disable toolchain, trust JAVA_HOME
      task.systemProperty("angela.distribution", requireNonNull(project.property("version")));
      task.systemProperty("angela.java.opts", "-Xms64m -Xmx512m -DIGNITE_UPDATE_NOTIFIER=false");
    });
  }
}
