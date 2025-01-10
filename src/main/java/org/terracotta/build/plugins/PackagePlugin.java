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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.internal.artifacts.JavaEcosystemSupport;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.terracotta.build.plugins.packaging.OsgiManifestJarExtension;
import org.terracotta.build.plugins.packaging.PackageInternal;
import org.terracotta.build.plugins.packaging.PackagingExtension;
import org.terracotta.build.plugins.packaging.PackagingExtensionInternal;

import javax.inject.Inject;

import static org.gradle.api.internal.tasks.JvmConstants.JAVA_COMPONENT_NAME;
import static org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.CONTENTS_API_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.CONTENTS_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.PROVIDED_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.UNPACKAGED_JAVA_RUNTIME;
import static org.terracotta.build.plugins.packaging.PackageInternal.camelPrefix;

/**
 * EhDistribute
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class PackagePlugin implements Plugin<Project> {

  public static final String COMMON_PREFIX = "common";

  @Inject
  protected abstract SoftwareComponentFactory getSoftwareComponentFactory();

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BasePlugin.class);

    AdhocComponentWithVariants javaComponent = getSoftwareComponentFactory().adhoc(JAVA_COMPONENT_NAME);
    project.getComponents().add(javaComponent);

    project.getTasks().withType(ShadowJar.class).configureEach(shadow -> shadow.getExtensions().create("osgi", OsgiManifestJarExtension.class, shadow));


    ConfigurationContainer configurations = project.getConfigurations();
    NamedDomainObjectProvider<DependencyScopeConfiguration> commonContentsApi = configurations.dependencyScope(camelPrefix(COMMON_PREFIX, CONTENTS_API_CONFIGURATION_NAME),
        c -> c.setDescription("API dependencies for all packages contents."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, CONTENTS_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonContentsApi.get()).setDescription("Implementation dependencies for all packages contents."));

    NamedDomainObjectProvider<DependencyScopeConfiguration> commonApi = configurations.dependencyScope(camelPrefix(COMMON_PREFIX, API_CONFIGURATION_NAME),
        c -> c.setDescription("API dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, IMPLEMENTATION_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonApi.get()).setDescription("Implementation dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, COMPILE_ONLY_API_CONFIGURATION_NAME),
        c -> c.setDescription("Compile-only API dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, RUNTIME_ONLY_CONFIGURATION_NAME),
        c -> c.setDescription("Runtime-only dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, PROVIDED_CONFIGURATION_NAME),
        c -> c.setDescription("'Provided' API dependencies for all packaged artifacts."));

    PackagingExtensionInternal packaging = (PackagingExtensionInternal) project.getExtensions().create(PackagingExtension.class, "packaging", PackagingExtensionInternal.class);
    packaging.getDefaultPackage().create();
    packaging.getVariants().all(PackageInternal::create);

    project.getPlugins().withType(MavenPublishPlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(PublishingExtension.class, publishing -> {
        publishing.getPublications().register("mavenJava", MavenPublication.class, mavenPublication -> {
          mavenPublication.from(javaComponent);
        });
      });
    });
  }

  public static void augmentAttributeSchema(AttributesSchema schema) {
    schema.attribute(Usage.USAGE_ATTRIBUTE, strategy -> strategy.getCompatibilityRules().add(UnpackagedJavaRuntimeCompatibility.class));
  }

  public static class UnpackagedJavaRuntimeCompatibility implements AttributeCompatibilityRule<Usage> {

    @Override
    public void execute(CompatibilityCheckDetails<Usage> details) {
      Usage consumer = details.getConsumerValue();

      if (consumer != null && UNPACKAGED_JAVA_RUNTIME.equals(consumer.getName())) {
        Usage producer = details.getProducerValue();
        if (producer != null) {
          switch (producer.getName()) {
            case Usage.JAVA_RUNTIME:
            case JavaEcosystemSupport.DEPRECATED_JAVA_RUNTIME_JARS:
              details.compatible();
              break;
          }
        }
      }
    }
  }
}