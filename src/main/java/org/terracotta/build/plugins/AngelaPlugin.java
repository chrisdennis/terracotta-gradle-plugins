package org.terracotta.build.plugins;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.testing.base.TestingExtension;

import javax.inject.Inject;
import java.nio.file.Files;
import java.util.Collections;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.terracotta.build.plugins.CustomLocalHostPlugin.hostsTaskName;

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

  public static final String KIT_CONFIGURATION_NAME = "angelaKit";
  public static final String FRAMEWORK_CONFIGURATION_NAME = "angela";
  public static final String SERVER_PLUGINS_CONFIGURATION_NAME = "angelaServerPlugins";

  private final JvmPluginServices jvmPluginServices;
  private final JavaForkOptionsFactory javaForkOptionsFactory;

  @Inject
  public AngelaPlugin(JvmPluginServices jvmPluginServices, JavaForkOptionsFactory javaForkOptionsFactory) {
    this.jvmPluginServices = jvmPluginServices;
    this.javaForkOptionsFactory = javaForkOptionsFactory;
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);
    project.getPlugins().apply(JvmTestSuitePlugin.class);
    
    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) project.getConfigurations();

    Provider<Directory> angelaDir = project.getLayout().getBuildDirectory().dir("angela");
    Provider<Directory> customKitDir = angelaDir.map(d -> d.dir("custom-tc-db-kit"));

    Configuration angela = configurations.bucket(FRAMEWORK_CONFIGURATION_NAME);
    angela.defaultDependencies(defaultDeps -> {
      defaultDeps.add(project.getDependencyFactory().create("org.terracotta", "angela", "[3,)"));
    });

    Configuration kit = configurations.resolvableBucket(KIT_CONFIGURATION_NAME);
    Configuration serverPlugins = configurations.bucket(SERVER_PLUGINS_CONFIGURATION_NAME);
    Configuration serverPluginsClasspath = ((ConfigurationContainerInternal) project.getConfigurations())
            .resolvable(SERVER_PLUGINS_CONFIGURATION_NAME + "Classpath").extendsFrom(serverPlugins);
    jvmPluginServices.configureAsRuntimeClasspath(serverPluginsClasspath);

    Provider<Sync> customKitPreparation = project.getTasks().register("angelaCustomKitPreparation", Sync.class, task -> {
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

    project.getExtensions().configure(TestingExtension.class, testing -> {
      testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
        project.getConfigurations().named(testSuite.getSources().getImplementationConfigurationName(), config -> {
          config.extendsFrom(angela);
        });
        testSuite.getTargets().configureEach(target -> {
          target.getTestTask().configure(task -> {
            task.dependsOn(customKitPreparation);
            task.systemProperty("IGNITE_UPDATE_NOTIFIER", "false");
            task.systemProperty("angela.rootDir", angelaDir.get().getAsFile().getAbsolutePath());
            task.systemProperty("angela.skipUninstall", "true");
            task.systemProperty("angela.tsa.fullLogging", "true");
            task.systemProperty("angela.igniteLogging", "false");
            task.systemProperty("angela.java.resolver", "user"); // disable toolchain, trust JAVA_HOME
            task.systemProperty("angela.distribution", requireNonNull(project.property("version")));
            task.getJvmArgumentProviders().add(() -> {
              if (serverPluginsClasspath.isEmpty()) {
                return singleton("-Dangela.kitInstallationDir=" + kit.getSingleFile().getAbsolutePath());
              } else {
                return singleton("-Dangela.kitInstallationDir=" + customKitDir.get().getAsFile().getAbsolutePath());
              }
            });

            JavaForkOptions angelaForkOptions = javaForkOptionsFactory.newDecoratedJavaForkOptions();
            task.getExtensions().add("angela", angelaForkOptions);
            angelaForkOptions.setMinHeapSize("64m");
            angelaForkOptions.setMaxHeapSize("512m");
            angelaForkOptions.systemProperty("IGNITE_UPDATE_NOTIFIER", false);

            task.getJvmArgumentProviders().add(() -> Collections.singleton("-Dangela.java.opts=" + angelaForkOptions.getAllJvmArgs().stream().collect(joining(" "))));

            project.getPlugins().withType(CustomLocalHostPlugin.class).configureEach(hostsPlugin -> {
              CustomHostPluginExtension hostsExtension = task.getExtensions().findByType(CustomHostPluginExtension.class);
              ListProperty<String> customHostList = hostsExtension.getCustomLocalHosts();
              task.getJvmArgumentProviders().add(() -> {
                if (!customHostList.get().isEmpty()) {
                  String customHostNames = String.join(" ", customHostList.get());
                  return singleton("-Dangela.additionalLocalHostnames=" + customHostNames);
                } else {
                  return emptyList();
                }
              });
              TaskProvider<CustomLocalHostPlugin.WriteHostsFile> writeHostsFile = project.getTasks().named(hostsTaskName(task), CustomLocalHostPlugin.WriteHostsFile.class);
              angelaForkOptions.getJvmArgumentProviders().add(() -> {
                if (!customHostList.get().isEmpty()) {
                  return singleton("-Djdk.net.hosts.file=" + writeHostsFile.get().getHostsFile().getAsFile().get().getAbsolutePath());
                } else {
                  return emptyList();
                }
              });
            });
          });
        });
      });
    });
  }
}
