package org.terracotta.build.plugins;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.internal.component.external.model.DefaultImmutableCapability;
import org.gradle.internal.component.external.model.ProjectDerivedCapability;
import org.gradle.testing.base.TestingExtension;

import java.io.File;
import java.util.jar.Attributes;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME;
import static org.terracotta.build.Utils.mapOf;

/**
 * Voltron library plugin.
 * <p>
 * Provides default setup for Voltron server-side libraries. This includes the standard dependencies provided by Voltron
 * on the compile and test classpaths, a service configuration for consumed service apis, an xml configuration for
 * legacy configuration code, and appropriate manifest classpath assembly.
 */
@SuppressWarnings("UnstableApiUsage")
public class VoltronPlugin implements Plugin<Project> {

  public static final String XML_CONFIG_VARIANT_NAME = "xml";
  public static final String VOLTRON_CONFIGURATION_NAME = "voltron";
  public static final String SERVICE_CONFIGURATION_NAME = "service";
  private static final String PROVIDED_CLASSPATH_CONFIGURATION_NAME = "providedClasspath";

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);

    DependencyFactory dependencyFactory = project.getDependencyFactory();

    SourceSet xml = project.getExtensions().getByType(SourceSetContainer.class).create("xml");
    project.getExtensions().configure(JavaPluginExtension.class, java ->
            java.registerFeature(XML_CONFIG_VARIANT_NAME, featureSpec -> featureSpec.usingSourceSet(xml)));

    project.getConfigurations().named(xml.getApiConfigurationName(), config -> {
      config.getDependencies().add(dependencyFactory.create(project));
    });
    project.getPlugins().withType(JvmTestSuitePlugin.class).configureEach(tests -> {
      project.getExtensions().configure(TestingExtension.class, testing -> {
        testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
          testSuite.getDependencies().getImplementation().add(dependencyFactory.create(project).capabilities(capabilities ->
                  capabilities.requireCapability(new ProjectDerivedCapability(project, XML_CONFIG_VARIANT_NAME))));
        });
      });
    });

    NamedDomainObjectProvider<DependencyScopeConfiguration> voltron = project.getConfigurations().dependencyScope(VOLTRON_CONFIGURATION_NAME,
        c -> c.setDescription("Dependencies provided by the platform Kit, either from server/lib or from plugins/api"));
    NamedDomainObjectProvider<DependencyScopeConfiguration> service = project.getConfigurations().dependencyScope(SERVICE_CONFIGURATION_NAME,
        c -> c.setDescription("Services consumed by this plugin"));
    NamedDomainObjectProvider<ResolvableConfiguration> providedClasspath = project.getConfigurations().resolvable(PROVIDED_CLASSPATH_CONFIGURATION_NAME, c -> c.extendsFrom(voltron.get(), service.get()));

    project.getConfigurations().named(JavaPlugin.API_CONFIGURATION_NAME, config -> config.extendsFrom(service.get()));
    project.getConfigurations().named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, config -> config.extendsFrom(voltron.get()));
    project.getConfigurations().named(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, config -> config.extendsFrom(voltron.get()));

    project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class, jar -> {
      NamedDomainObjectProvider<Configuration> runtimeClasspath = project.getConfigurations().named(RUNTIME_CLASSPATH_CONFIGURATION_NAME);

      jar.getInputs().property(RUNTIME_CLASSPATH_CONFIGURATION_NAME, runtimeClasspath.flatMap(c -> c.getElements().map(e -> e.stream().map(f -> f.getAsFile().getName()).collect(toSet()))));
      jar.getInputs().property(PROVIDED_CLASSPATH_CONFIGURATION_NAME, providedClasspath.flatMap(c -> c.getElements().map(e -> e.stream().map(f -> f.getAsFile().getName()).collect(toSet()))));

      Provider<String> voltronClasspath = runtimeClasspath.zip(providedClasspath, FileCollection::minus)
              .map(c -> c.getFiles().stream().map(File::getName).collect(joining(" ")));
      jar.manifest(manifest -> manifest.attributes(mapOf(Attributes.Name.CLASS_PATH.toString(), voltronClasspath)));
    });

    project.getConfigurations().named(xml.getApiConfigurationName(), config -> config.extendsFrom(service.get()));
    project.getConfigurations().named(xml.getCompileOnlyConfigurationName(), config -> config.extendsFrom(voltron.get()));
  }

  public static Capability xmlConfigFeatureCapability(ModuleDependency dependency) {
    if (dependency instanceof ProjectDependency) {
      return new ProjectDerivedCapability(((ProjectDependency) dependency).getDependencyProject(), XML_CONFIG_VARIANT_NAME);
    } else {
      return new DefaultImmutableCapability(dependency.getGroup(), dependency.getName() + "-" + XML_CONFIG_VARIANT_NAME, null);
    }
  }
}
