package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testing.base.TestingExtension;

import javax.inject.Inject;
import java.nio.file.Files;
import java.util.function.Predicate;
import java.util.stream.Stream;

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

  public static final String KIT_CONFIGURATION_NAME = "galvanKit";
  public static final String FRAMEWORK_CONFIGURATION_NAME = "galvan";
  public static final String SERVER_PLUGINS_CONFIGURATION_NAME = "galvanServerPlugins";

  private final JvmPluginServices jvmPluginServices;

  @Inject
  public GalvanPlugin(JvmPluginServices jvmPluginServices) {
    this.jvmPluginServices = jvmPluginServices;
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) project.getConfigurations();

    Provider<Directory> galvanDir = project.getLayout().getBuildDirectory().dir("galvan");
    Provider<Directory> customKitDir = galvanDir.map(d -> d.dir("custom-tc-db-kit"));

    Configuration galvan = configurations.bucket(FRAMEWORK_CONFIGURATION_NAME);
    galvan.defaultDependencies(defaultDeps -> {
      defaultDeps.add(project.getDependencyFactory().create("org.terracotta", "galvan-platform-support", "[5,)"));
    });

    project.getPlugins().withType(JvmTestSuitePlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(TestingExtension.class, testing -> {
        testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
          project.getConfigurations().named(testSuite.getSources().getImplementationConfigurationName(), config -> {
            config.extendsFrom(galvan);
          });
        });
      });
    });

    Configuration kit = configurations.resolvableBucket(KIT_CONFIGURATION_NAME);
    Configuration serverPlugins = configurations.bucket(SERVER_PLUGINS_CONFIGURATION_NAME);
    Configuration serverPluginsClasspath = ((ConfigurationContainerInternal) project.getConfigurations())
            .resolvable(SERVER_PLUGINS_CONFIGURATION_NAME + "Classpath").extendsFrom(serverPlugins);
    jvmPluginServices.configureAsRuntimeClasspath(serverPluginsClasspath);

    Provider<Sync> customKitPreparation = project.getTasks().register("galvanCustomKitPreparation", Sync.class, task -> {
      task.onlyIf(t -> !serverPluginsClasspath.isEmpty());

      task.from(kit);
      task.into(customKitDir);

      Predicate<String> pruning = Stream.of("server/plugins/api", "server/lib")
              .map(dir -> task.getDestinationDir().toPath().resolve(dir))
              .map(dir -> (Predicate<String>) (String file) -> Files.exists(dir.resolve(file)))
              .reduce(Predicate::or).orElse(f -> false);

      task.from(serverPluginsClasspath, spec -> {
        spec.into("server/plugins/lib");
        spec.eachFile(fcd -> {
          if (pruning.test(fcd.getName())) {
            fcd.exclude();
          }
        });
      });
    });

    project.getTasks().withType(Test.class).configureEach(task -> {
      task.dependsOn(customKitPreparation);

      /*
       * Do **not** convert the anonymous Action here to a lambda expression - it will break Gradle's up-to-date tracking
       * and cause tasks to be needlessly rerun.
       */
      //noinspection Convert2Lambda
      task.doFirst(new Action<Task>() {
        @Override
        public void execute(Task t) {
          if (serverPluginsClasspath.isEmpty()) {
            task.systemProperty("kitInstallationPath", kit.getSingleFile().getAbsolutePath());
          } else {
            task.systemProperty("kitInstallationPath", customKitDir.get().getAsFile().getAbsolutePath());
          }
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
