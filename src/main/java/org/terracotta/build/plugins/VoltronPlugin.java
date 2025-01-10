/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terracotta.build.plugins;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
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

    NamedDomainObjectProvider<Configuration> voltron = project.getConfigurations().register(VOLTRON_CONFIGURATION_NAME, config -> {
      config.setDescription("Dependencies provided by the platform Kit, either from server/lib or from plugins/api");
      config.setCanBeResolved(true);
      config.setCanBeConsumed(true);
    });
    NamedDomainObjectProvider<Configuration> service = project.getConfigurations().register(SERVICE_CONFIGURATION_NAME, config -> {
      config.setDescription("Services consumed by this plugin");
      config.setCanBeResolved(true);
      config.setCanBeConsumed(true);
    });
    project.getConfigurations().named(JavaPlugin.API_CONFIGURATION_NAME, config -> config.extendsFrom(service.get()));
    project.getConfigurations().named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, config -> config.extendsFrom(voltron.get()));
    project.getConfigurations().named(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, config -> config.extendsFrom(voltron.get()));

    project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class, jar -> {
      NamedDomainObjectProvider<Configuration> runtimeClasspath = project.getConfigurations().named(RUNTIME_CLASSPATH_CONFIGURATION_NAME);

      jar.getInputs().property(RUNTIME_CLASSPATH_CONFIGURATION_NAME, runtimeClasspath.flatMap(c -> c.getElements().map(e -> e.stream().map(f -> f.getAsFile().getName()).collect(toSet()))));
      jar.getInputs().property(SERVICE_CONFIGURATION_NAME, service.flatMap(c -> c.getElements().map(e -> e.stream().map(f -> f.getAsFile().getName()).collect(toSet()))));
      jar.getInputs().property(VOLTRON_CONFIGURATION_NAME, voltron.flatMap(c -> c.getElements().map(e -> e.stream().map(f -> f.getAsFile().getName()).collect(toSet()))));

      Provider<String> voltronClasspath = runtimeClasspath.zip(service.zip(voltron, FileCollection::plus), FileCollection::minus)
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
