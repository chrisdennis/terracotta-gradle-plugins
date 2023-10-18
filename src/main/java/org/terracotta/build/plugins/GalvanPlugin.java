package org.terracotta.build.plugins;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.file.Directory;
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
import java.util.Collections;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Collections.singleton;

/**
 * Galvsn based testing plugin.
 * <p>
 * This plugin ensures the test task of the associated project is able to run Galvan tests. That consists of:
 * <ul>
 *   <li>Adding the galvan-support-ee test dependency</li>
 *   <li>Ensuring test test-kit has been built and linking to it properly</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public class GalvanPlugin implements Plugin<Project> {

  public static final String KIT_CONFIGURATION_NAME = "galvanKit";
  public static final String KIT_PATH_CONFIGURATION_NAME = "galvanKitPath";
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

    ConfigurationContainer configurations = project.getConfigurations();

    Provider<Directory> galvanDir = project.getLayout().getBuildDirectory().dir("galvan");
    Provider<Directory> customKitDir = galvanDir.map(d -> d.dir("custom-tc-db-kit"));

    NamedDomainObjectProvider<DependencyScopeConfiguration> galvan = configurations.dependencyScope(FRAMEWORK_CONFIGURATION_NAME,
            c -> c.defaultDependencies(defaultDeps -> {
              defaultDeps.add(project.getDependencyFactory().create("org.terracotta", "galvan-platform-support", "[5,)"));
            })
    );

    project.getPlugins().withType(JvmTestSuitePlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(TestingExtension.class, testing -> {
        testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
          project.getConfigurations().named(testSuite.getSources().getImplementationConfigurationName(),
                  config -> config.extendsFrom(galvan.get()));
        });
      });
    });

    NamedDomainObjectProvider<DependencyScopeConfiguration> kit = configurations.dependencyScope(KIT_CONFIGURATION_NAME);
    NamedDomainObjectProvider<ResolvableConfiguration> kitPath = configurations.resolvable(KIT_PATH_CONFIGURATION_NAME, c -> c.extendsFrom(kit.get()));
    NamedDomainObjectProvider<DependencyScopeConfiguration> serverPlugins = configurations.dependencyScope(SERVER_PLUGINS_CONFIGURATION_NAME);
    NamedDomainObjectProvider<ResolvableConfiguration> serverPluginsClasspath = configurations.resolvable(SERVER_PLUGINS_CONFIGURATION_NAME + "Classpath", c -> {
      c.extendsFrom(serverPlugins.get());
      jvmPluginServices.configureAsRuntimeClasspath(c);
    });

    Provider<Sync> customKitPreparation = project.getTasks().register("galvanCustomKitPreparation", Sync.class, task -> {
      task.onlyIf(t -> !serverPluginsClasspath.get().isEmpty());

      task.from(kitPath);
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

      task.getJvmArgumentProviders().add(() -> {
        if (customKitPreparation.get().getState().getSkipped()) {
          return Collections.singleton("-DkitInstallationPath=" + kitPath.get().getSingleFile().getAbsolutePath());
        } else {
          return Collections.singleton("-DkitInstallationPath=" + customKitDir.get().getAsFile().getAbsolutePath());
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
