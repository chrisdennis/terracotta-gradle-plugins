package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.internal.component.external.model.ImmutableCapability;
import org.gradle.internal.component.external.model.ProjectDerivedCapability;
import org.gradle.internal.fingerprint.NameOnlyInputNormalizer;

import java.io.File;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.terracotta.build.Utils.mapOf;

/**
 * Voltron library plugin.
 * <p>
 * Provides default setup for Voltron server-side libraries. This includes the standard dependencies provided by Voltron
 * on the compile and test classpaths, a service configuration for consumed service apis, an xml configuration for
 * legacy configuration code, and appropriate manifest classpath assembly.
 */
public class VoltronPlugin implements Plugin<Project> {

  private static final String XML_CONFIG_VARIANT_NAME = "xml";

  @Override
  public void apply(Project project) {
    project.getPlugins().withType(JavaPlugin.class).configureEach(plugin -> {
      DependencyHandler dependencies = project.getDependencies();

      SourceSet test = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.TEST_SOURCE_SET_NAME);
      SourceSet xml = project.getExtensions().getByType(SourceSetContainer.class).create("xml");
      project.getExtensions().configure(JavaPluginExtension.class, java ->
              java.registerFeature(XML_CONFIG_VARIANT_NAME, featureSpec -> featureSpec.usingSourceSet(xml)));
      dependencies.add(xml.getApiConfigurationName(), dependencies.create(project));
      ProjectDependency testDependency = (ProjectDependency) dependencies.add(test.getImplementationConfigurationName(), dependencies.create(project));
      testDependency.capabilities(capabilitiesHandler -> capabilitiesHandler.requireCapability(new ProjectDerivedCapability(project, XML_CONFIG_VARIANT_NAME)));

      NamedDomainObjectProvider<Configuration> voltron = project.getConfigurations().register("voltron", config -> {
        config.setDescription("Dependencies provided by the platform Kit, either from server/lib or from plugins/api");
        config.setCanBeResolved(true);
        config.setCanBeConsumed(true);

        config.getDependencies().addAll(asList(
                dependencies.create("org.terracotta:entity-server-api:" + project.property("terracottaApisVersion")),
                dependencies.create("org.terracotta:standard-cluster-services:" + project.property("terracottaApisVersion")),
                dependencies.create("org.terracotta:packaging-support:" + project.property("terracottaApisVersion")),
                dependencies.create("org.slf4j:slf4j-api:" + project.property("slf4jVersion")),

                // management api
                dependencies.create("org.terracotta.management:monitoring-service-api:" + project.property("terracottaPlatformVersion")),
                dependencies.create("org.terracotta.management:management-registry:" + project.property("terracottaPlatformVersion")),

                // dynamic config api
                dependencies.create("org.terracotta.dynamic-config.server:dynamic-config-server-api:" + project.property("terracottaPlatformVersion"))
        ));
      });
      NamedDomainObjectProvider<Configuration> service = project.getConfigurations().register("service", config -> {
        config.setDescription("Services consumed by this plugin");
        config.setCanBeResolved(true);
        config.setCanBeConsumed(true);
      });
      project.getConfigurations().named(JavaPlugin.API_CONFIGURATION_NAME, config -> config.extendsFrom(service.get()));
      project.getConfigurations().named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, config -> config.extendsFrom(voltron.get()));
      project.getConfigurations().named(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, config -> config.extendsFrom(voltron.get()));
      /*
       * Do **not** convert the anonymous Action here to a lambda expression - it will break Gradle's up-to-date tracking
       * and cause tasks to be needlessly rerun.
       */
      project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class, jar -> {
        Configuration runtimeClasspath = project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        jar.getInputs().files(runtimeClasspath).withPropertyName("runtimeClasspath").withNormalizer(NameOnlyInputNormalizer.class);
        jar.doFirst(new Action<Task>() {
          @Override
          public void execute(Task task) {
            jar.manifest(manifest -> {
              FileCollection classpath = runtimeClasspath.minus(service.get()).minus(voltron.get());
              manifest.attributes(mapOf("Class-Path", classpath.getFiles().stream().map(File::getName).collect(Collectors.joining(" "))));
            });
          }
        });
      });

      project.getConfigurations().named(xml.getApiConfigurationName(), config -> config.extendsFrom(service.get()));
      project.getConfigurations().named(xml.getCompileOnlyConfigurationName(), config -> config.extendsFrom(voltron.get()));
    });
  }

  public static Capability xmlConfigFeatureCapability(ModuleDependency dependency) {
    if (dependency instanceof ProjectDependency) {
      return new ProjectDerivedCapability(((ProjectDependency) dependency).getDependencyProject(), XML_CONFIG_VARIANT_NAME);
    } else {
      return new ImmutableCapability(dependency.getGroup(), dependency.getName() + "-" + XML_CONFIG_VARIANT_NAME, null);
    }
  }
}
