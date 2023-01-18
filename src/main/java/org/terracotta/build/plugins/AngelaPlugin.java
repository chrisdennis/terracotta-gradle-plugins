package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.testing.base.TestingExtension;
import org.terracotta.build.PluginUtils;

import java.nio.file.Files;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

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

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);

    ServiceRegistry projectServices = ((ProjectInternal) project).getServices();
    JvmPluginServices jvmPluginServices = projectServices.get(JvmPluginServices.class);

    Provider<Directory> angelaDir = project.getLayout().getBuildDirectory().dir("angela");
    Provider<Directory> customKitDir = angelaDir.map(d -> d.dir("custom-tc-db-kit"));

    Configuration angela = PluginUtils.createBucket(project, FRAMEWORK_CONFIGURATION_NAME);
    angela.defaultDependencies(defaultDeps -> {
      defaultDeps.add(project.getDependencyFactory().create("org.terracotta", "angela", "[3,)"));
    });

    project.getPlugins().withType(JvmTestSuitePlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(TestingExtension.class, testing -> {
        testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
          project.getConfigurations().named(testSuite.getSources().getImplementationConfigurationName(), config -> {
            config.extendsFrom(angela);
          });
        });
      });
    });

    Provider<Configuration> kit = project.getConfigurations().register(KIT_CONFIGURATION_NAME);

    Configuration serverPlugins = PluginUtils.createBucket(project, SERVER_PLUGINS_CONFIGURATION_NAME);
    Configuration serverPluginsClasspath = jvmPluginServices.createResolvableConfiguration(SERVER_PLUGINS_CONFIGURATION_NAME + "Classpath", builder -> {
      builder.extendsFrom(serverPlugins).requiresJavaLibrariesRuntime();
    });

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
    project.getTasks().withType(Test.class, task -> {
      task.dependsOn(customKitPreparation);

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
          if (serverPluginsClasspath.isEmpty()) {
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
