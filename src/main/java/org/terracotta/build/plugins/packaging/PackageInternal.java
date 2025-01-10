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

package org.terracotta.build.plugins.packaging;

import aQute.bnd.osgi.Constants;
import aQute.bnd.version.MavenVersion;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ConsumableConfiguration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.jvm.internal.JvmEcosystemAttributesDetails;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.internal.resolve.ArtifactResolveException;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.terracotta.build.plugins.JavaVersionPlugin;
import org.terracotta.build.plugins.PackagePlugin;

import javax.inject.Inject;
import java.io.File;
import java.util.Collections;
import java.util.Optional;

import static org.gradle.api.artifacts.Dependency.DEFAULT_CONFIGURATION;
import static org.gradle.api.attributes.DocsType.JAVADOC;
import static org.gradle.api.attributes.DocsType.SOURCES;
import static org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE;
import static org.gradle.api.internal.tasks.JvmConstants.JAVA_COMPONENT_NAME;
import static org.gradle.api.plugins.JavaBasePlugin.DOCUMENTATION_GROUP;
import static org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.JAR_TASK_NAME;
import static org.gradle.api.plugins.JavaPlugin.JAVADOC_ELEMENTS_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.JAVADOC_TASK_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME;
import static org.terracotta.build.PluginUtils.capitalize;
import static org.terracotta.build.Utils.coordinate;
import static org.terracotta.build.Utils.mapOf;

@SuppressWarnings("UnstableApiUsage")
public abstract class PackageInternal implements Package {

  public static final String UNPACKAGED_JAVA_RUNTIME = "unpackaged-java-runtime";

  public static final String CONTENTS_CONFIGURATION_NAME = "contents";
  public static final String CONTENTS_API_CONFIGURATION_NAME = "contentsApi";
  public static final String CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "contentsRuntimeClasspath";
  public static final String CONTENTS_SOURCES_CONFIGURATION_NAME = "contentsSources";
  public static final String CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME = "contentsSourcesElements";

  public static final String PROVIDED_CONFIGURATION_NAME = "provided";

  public static final String UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME = "unpackagedApiElements";
  public static final String UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME = "unpackagedRuntimeElements";
  public static final String MAXIMAL_UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "maximalUnpackagedRuntimeClasspath";

  public static final String PACKAGED_API_ELEMENTS_CONFIGURATION_NAME = "packagedApiElements";
  public static final String PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME = "packagedRuntimeElements";
  public static final String PACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "packagedRuntimeClasspath";

  public static final String SOURCES_TASK_NAME = "sources";

  @Inject
  public abstract Project getProject();

  @Inject
  public abstract JvmPluginServices getJvmPluginServices();

  public abstract NamedDomainObjectContainer<OptionalFeatureInternal> getOptionalFeatures();

  public abstract DomainObjectSet<Capability> getCapabilities();

  @Override
  public void withSourcesJar() {
    TaskContainer tasks = getProject().getTasks();

    TaskProvider<Jar> sourcesJar = tasks.register(camelName("sourcesJar"), Jar.class, jar -> {
      jar.setDescription(description("Assembles a jar archive containing {0} packaged sources."));
      jar.setGroup(BasePlugin.BUILD_GROUP);
      jar.from(tasks.named(camelName(SOURCES_TASK_NAME)));
      jar.from(tasks.named(camelName(JAR_TASK_NAME)), spec -> spec.include("META-INF/**", "LICENSE", "NOTICE"));
      jar.getArchiveClassifier().set(kebabName("sources"));
    });

    ConfigurationContainer configurations = getProject().getConfigurations();
    Provider<ConsumableConfiguration> sourcesElements = configurations.consumable(camelName(SOURCES_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Sources elements for {0} packaged artifact."));
      getJvmPluginServices().configureAttributes(c, details -> details.runtimeUsage().withExternalDependencies().documentation(SOURCES));
      c.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(sourcesJar);
      });
    });

    getProject().getComponents().named(JAVA_COMPONENT_NAME, AdhocComponentWithVariants.class,
        java -> java.addVariantsFromConfiguration(sourcesElements.get(), variantDetails -> {}));

    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> task.dependsOn(sourcesJar));
  }

  @Override
  public void withJavadocJar(Action<Javadoc> action) {
    Javadoc javadocSettings = getProject().getObjects().newInstance(JavadocInternal.class);
    action.execute(javadocSettings);

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();
    TaskContainer tasks = getProject().getTasks();

    if (javadocSettings.getArtifact().isPresent()) {
      Provider<DependencyScopeConfiguration> javadoc = configurations.dependencyScope(camelName("inheritedJavadoc"), c -> c
          .setDescription(description("Dependencies for {0} inherited javadoc."))
          .getDependencies().add(javadocSettings.getArtifact().get())
      );

      Provider<ResolvableConfiguration> javadocJars = configurations.resolvable(camelName("inheritedJavadocJars"), c -> {
        c.setDescription("Inherited javadoc files for merging.");
        c.extendsFrom(javadoc.get());
        getJvmPluginServices().configureAttributes(c, details -> details.documentation(JAVADOC).asJar());
      });

      TaskProvider<Jar> javadocJar = tasks.register(camelName("javadocJar"), Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the inherited javadoc.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadocJars.flatMap(FileCollection::getElements).map(e -> e.stream().map(getProject()::zipTree).toArray())).exclude("LICENSE");
        jar.getArchiveClassifier().set(kebabName("javadoc"));
      });

      Provider<ConsumableConfiguration> javadocElements = configurations.consumable(camelName(JAVADOC_ELEMENTS_CONFIGURATION_NAME), c -> {
        getJvmPluginServices().configureAttributes(c, details -> details.runtimeUsage().withExternalDependencies().documentation(JAVADOC));
        c.outgoing(o -> {
          getCapabilities().all(o::capability);
          o.artifact(javadocJar);
        });
      });

      getProject().getComponents().named("java", AdhocComponentWithVariants.class,
          java -> java.addVariantsFromConfiguration(javadocElements.get(), variantDetails -> {}));

      tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> task.dependsOn(javadocJar));

    } else {
      Provider<DependencyScopeConfiguration> additionalJavadoc = configurations.dependencyScope(camelName("additionalJavadoc"), c -> {
        c.setDescription("Additional javadoc generation dependencies.");
        javadocSettings.getClasspath().get().forEach(notation -> c.getDependencies().add(notation));
      });

      Provider<ResolvableConfiguration> javadocClasspath = configurations.resolvable(camelName("javadocClasspath"), c -> {
        c.setDescription(description("Classpath for {0} javadoc generation."));
        c.extendsFrom(additionalJavadoc.get(), configurations.getByName(camelName(CONTENTS_CONFIGURATION_NAME)));
        getJvmPluginServices().configureAsRuntimeClasspath(c);
      });

      TaskProvider<org.gradle.api.tasks.javadoc.Javadoc> javadoc = tasks.register(camelName(JAVADOC_TASK_NAME), org.gradle.api.tasks.javadoc.Javadoc.class, task -> {
        task.setDescription("Generates Javadoc API documentation for {0} packaged source code.");
        task.setGroup(DOCUMENTATION_GROUP);
        task.setTitle(getProject().getName() + " " + getProject().getVersion() + " API");
        task.source(tasks.named(camelName(SOURCES_TASK_NAME)));
        task.include("**/*.java");
        task.getModularity().getInferModulePath().set(false);
        task.setClasspath(javadocClasspath.get());
        task.setDestinationDir(getProject().getLayout().getBuildDirectory().dir(kebabName(JAVADOC_TASK_NAME)).get().getAsFile());
      });
      TaskProvider<Jar> javadocJar = tasks.register(camelName("javadocJar"), Jar.class, jar -> {
        jar.setDescription(description("Assembles a jar archive containing {0} packaged javadoc."));
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadoc);
        jar.getArchiveClassifier().set(kebabName("javadoc"));
      });

      Provider<ConsumableConfiguration> javadocElements = configurations.consumable(camelName(JAVADOC_ELEMENTS_CONFIGURATION_NAME), c -> {
        c.setDescription(description("Javadoc elements for {0} packaged artifact."));
        getJvmPluginServices().configureAttributes(c, details -> details.runtimeUsage().withExternalDependencies().documentation(JAVADOC));
        c.outgoing(o -> {
          getCapabilities().all(o::capability);
          o.artifact(javadocJar);
        });
      });

      getProject().getComponents().named("java", AdhocComponentWithVariants.class,
          java -> java.addVariantsFromConfiguration(javadocElements.get(), variantDetails -> {}));

      tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> task.dependsOn(javadocJar));
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  public void create() {
    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();
    DependencyHandler dependencies = getProject().getDependencies();
    TaskContainer tasks = getProject().getTasks();
    Provider<Integer> javaCompileVersion = getProject().getExtensions().getByType(JavaVersionPlugin.JavaVersions.class).getCompileVersion().map(JavaLanguageVersion::asInt);

    Provider<Configuration> commonContentsApi = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, CONTENTS_API_CONFIGURATION_NAME));
    Provider<Configuration> commonContents = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, CONTENTS_CONFIGURATION_NAME));
    Provider<Configuration> commonApi = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, API_CONFIGURATION_NAME));
    Provider<Configuration> commonImplementation = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, IMPLEMENTATION_CONFIGURATION_NAME));
    Provider<Configuration> commonCompileOnlyApi = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, COMPILE_ONLY_API_CONFIGURATION_NAME));
    Provider<Configuration> commonRuntimeOnly = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, RUNTIME_ONLY_CONFIGURATION_NAME));
    Provider<Configuration> commonProvided = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, PROVIDED_CONFIGURATION_NAME));

    Provider<DependencyScopeConfiguration> contentsApi = configurations.dependencyScope(camelName(CONTENTS_API_CONFIGURATION_NAME), c -> c
        .extendsFrom(commonContentsApi.get())
        .setDescription(description("API dependencies for {0} package contents.")));
    Provider<DependencyScopeConfiguration> contents = configurations.dependencyScope(camelName(CONTENTS_CONFIGURATION_NAME), c -> c
        .extendsFrom(commonContents.get(), contentsApi.get())
        .setDescription(description("Implementation dependencies for {0} package contents."))
    );

    NamedDomainObjectProvider<ResolvableConfiguration> contentsRuntimeClasspath = configurations.resolvable(camelName(CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME), c -> {
      c.extendsFrom(contents.get());
      c.setDescription(description("Runtime classpath of {0} package contents."));
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      getJvmPluginServices().configureAsRuntimeClasspath(c);
    });

    /*
     * The variant metadata rules are not complex enough, nor applied uniformly enough to give us the "transient sources"
     * configuration that we need. Instead, we populate the contentSourcesElements configuration using the resolved
     * artifacts of the shadow contents configuration.
     */
    Provider<DependencyScopeConfiguration> contentsSources = configurations.dependencyScope(camelName(CONTENTS_SOURCES_CONFIGURATION_NAME), c -> c
        .setVisible(false)
        .withDependencies(config -> contentsRuntimeClasspath.get().getIncoming().artifactView(view -> {}).getArtifacts()
            .getResolvedArtifacts().get().stream().map(ResolvedArtifactResult::getVariant).forEach(variant -> {
              ComponentIdentifier id = variant.getOwner();
              Dependency dependency;
              if (id instanceof ProjectComponentIdentifier) {
                ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) id;
                dependency = dependencies.project(coordinate(projectId.getProjectPath()));
              } else if (id instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier moduleId = (ModuleComponentIdentifier) id;
                dependency = dependencies.create(mapOf("group", moduleId.getGroup(), "name", moduleId.getModule(), "version", moduleId.getVersion()));
              } else {
                throw new GradleException("Unsupported component identifier type: " + id);
              }
              ((ModuleDependency) dependency).capabilities(o -> variant.getCapabilities().forEach(o::requireCapability));
              config.add(dependency);
            })
        )
    );
    Provider<ResolvableConfiguration> contentsSourcesElements = configurations.resolvable(camelName(CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Source artifacts for {0} package contents."));
      c.extendsFrom(contentsSources.get());
      getJvmPluginServices().configureAttributes(c, details -> details.documentation(SOURCES));
      c.resolutionStrategy(resolutionStrategy -> ((ResolutionStrategyInternal) resolutionStrategy).assumeFluidDependencies());
    });


    Provider<FileCollection> sourcesTree = getProject().provider(() -> contentsSourcesElements.get().getResolvedConfiguration().getLenientConfiguration().getAllModuleDependencies().stream().flatMap(d -> d.getModuleArtifacts().stream()).map(artifact -> {
      try {
        return Optional.of(artifact.getFile());
      } catch (ArtifactResolveException e) {
        return Optional.<File>empty();
      }
    }).filter(Optional::isPresent).map(Optional::get).distinct().map(file -> {
      if (file.isFile()) {
        return getProject().zipTree(file);
      } else {
        return getProject().fileTree(file);
      }
    }).reduce(FileTree::plus).orElse(getProject().files().getAsFileTree()));

    TaskProvider<Sync> sources = tasks.register(camelName(SOURCES_TASK_NAME), Sync.class, sync -> {
      sync.setDescription(description("Collects the sources contributing to {0} packaged artifact."));
      sync.setGroup(DOCUMENTATION_GROUP);
      sync.dependsOn(contentsSourcesElements);
      sync.from(sourcesTree, spec -> spec.exclude("META-INF/**"));
      sync.into(getProject().getLayout().getBuildDirectory().dir(kebabName("sources")));
    });

    Provider<DependencyScopeConfiguration> api = configurations.dependencyScope(camelName(API_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonApi.get()).setDescription(description("API dependencies for {0} packaged artifact.")));
    NamedDomainObjectProvider<DependencyScopeConfiguration> implementation = configurations.dependencyScope(camelName(IMPLEMENTATION_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonImplementation.get(), api.get()).setDescription(description("Implementation dependencies for {0} packaged artifact.")));
    Provider<DependencyScopeConfiguration> compileOnlyApi = configurations.dependencyScope(camelName(COMPILE_ONLY_API_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonCompileOnlyApi.get()).setDescription(description("Compile-only API dependencies for {0} packaged artifact.")));
    Provider<DependencyScopeConfiguration> runtimeOnly = configurations.dependencyScope(camelName(RUNTIME_ONLY_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonRuntimeOnly.get()).setDescription(description("Runtime-only dependencies for {0} packaged artifact.")));
    Provider<DependencyScopeConfiguration> provided = configurations.dependencyScope(camelName(PROVIDED_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonProvided.get()).setDescription(description("'Provided' API dependencies for {0} packaged artifact.")));

    configurations.consumable(camelName(UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("API elements for {0} unpackaged contents."));
      c.extendsFrom(api.get(), compileOnlyApi.get(), contentsApi.get());
      getJvmPluginServices().configureAttributes(c, details -> details.apiUsage().library().asJar().withExternalDependencies());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> getCapabilities().all(o::capability));
    });

    configurations.consumable(camelName(UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Runtime elements for {0} unpackaged contents."));
      c.extendsFrom(implementation.get(), runtimeOnly.get(), contents.get());
      getJvmPluginServices().configureAttributes(c, details -> details.library().asJar().withExternalDependencies());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
          .attribute(Usage.USAGE_ATTRIBUTE, getProject().getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));
      c.outgoing(o -> getCapabilities().all(o::capability));
    });

    Provider<ResolvableConfiguration> maximalUnpackagedRuntimeClasspath =
        configurations.resolvable(camelName(MAXIMAL_UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME), c -> {
          c.setDescription(description("Maximal (incl all optional features) runtime classpath of {0} unpackaged contents."));
          c.extendsFrom(implementation.get(), runtimeOnly.get());
          getJvmPluginServices().configureAttributes(c, details -> details.withExternalDependencies());
          c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
              .attribute(Usage.USAGE_ATTRIBUTE, getProject().getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));
        });

    TaskProvider<ShadowJar> shadowJar = tasks.register(camelName(JAR_TASK_NAME), ShadowJar.class, shadow -> {
      shadow.setDescription(description("Assembles a jar archive containing {0} packaged classes."));
      shadow.setGroup(BasePlugin.BUILD_GROUP);

      shadow.setConfigurations(Collections.singletonList(contentsRuntimeClasspath.get()));
      shadow.getArchiveClassifier().set(kebabName(""));
      shadow.mergeServiceFiles();

      shadow.exclude("META-INF/MANIFEST.MF");
    });

    configurations.named(DEFAULT_CONFIGURATION).configure(c -> c.outgoing(o -> o.artifact(shadowJar)));

    Provider<ConsumableConfiguration> packagedApiElements = configurations.consumable(camelName(PACKAGED_API_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("API elements for {0} packaged artifact."));
      c.extendsFrom(api.get(), compileOnlyApi.get());
      getJvmPluginServices().configureAttributes(c, details -> details.apiUsage().library().asJar().withEmbeddedDependencies());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(shadowJar);
      });
    });

    Provider<ConsumableConfiguration> packagedRuntimeElements = configurations.consumable(camelName(PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Runtime elements for {0} packaged artifact."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAttributes(c, details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));

      c.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(shadowJar);
      });
    });

    Provider<ResolvableConfiguration> packagedRuntimeClasspath = configurations.resolvable(camelName(PACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Runtime classpath of {0} packaged artifact."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAttributes(c, details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    });


    contentsRuntimeClasspath.configure(c -> c.getIncoming().beforeResolve(config -> {
      maximalUnpackagedRuntimeClasspath.get().getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
        ModuleVersionIdentifier identifier = resolvedArtifact.getModuleVersion().getId();
        c.exclude(mapOf(String.class, String.class, "group", identifier.getGroup(), "module", identifier.getName()));
      });
    }));

    shadowJar.configure(shadow -> {
      OsgiManifestJarExtension osgi = shadow.getExtensions().findByType(OsgiManifestJarExtension.class);
      osgi.getClasspath().from(packagedRuntimeClasspath);
      osgi.getSources().from(sources);

      osgi.instruction(Constants.BUNDLE_VERSION, new MavenVersion(getProject().getVersion().toString()).getOSGiVersion().toString());
    });

    getProject().getComponents().named("java", AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(packagedApiElements.get(), variantDetails -> variantDetails.mapToMavenScope("compile"));
      java.addVariantsFromConfiguration(packagedRuntimeElements.get(), variantDetails -> variantDetails.mapToMavenScope("runtime"));
    });

    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> task.dependsOn(shadowJar));

    implementation.configure(c -> provided.get().getDependencies().configureEach(dependency ->
        c.getDependencyConstraints().add(DefaultDependencyConstraint.strictly(dependency.getGroup(), dependency.getName(), dependency.getVersion()))));

    getOptionalFeatures().all(this::createOptionalFeature);
  }

  public void createOptionalFeature(OptionalFeatureInternal feature) {
    String featureName = feature.getName();

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();

    Provider<DependencyScopeConfiguration> api = configurations.dependencyScope(camelName(camelPrefix(featureName, API_CONFIGURATION_NAME)),
        c -> c.setDescription(description("API dependencies for {0} packaged artifact '" + featureName + "' feature.")));
    Provider<DependencyScopeConfiguration> implementation = configurations.dependencyScope(camelName(camelPrefix(featureName, IMPLEMENTATION_CONFIGURATION_NAME)),
        c -> c.extendsFrom(api.get()).setDescription(description("Implementation dependencies for {0} packaged artifact '" + featureName + "' feature.")));
    Provider<DependencyScopeConfiguration> compileOnlyApi = configurations.dependencyScope(camelName(camelPrefix(featureName, COMPILE_ONLY_API_CONFIGURATION_NAME)),
        c -> c.setDescription(description("Compile-only dependencies for {0} packaged artifact '" + featureName + "' feature.")));
    Provider<DependencyScopeConfiguration> runtimeOnly = configurations.dependencyScope(camelName(camelPrefix(featureName, RUNTIME_ONLY_CONFIGURATION_NAME)),
        c -> c.setDescription(description("Runtime-only dependencies for {0} packaged artifact '" + featureName + "' feature.")));


    configurations.named(camelName(MAXIMAL_UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME)).configure(c -> c.extendsFrom(implementation.get(), runtimeOnly.get()));

    Provider<Integer> javaCompileVersion = getProject().getExtensions().getByType(JavaVersionPlugin.JavaVersions.class).getCompileVersion().map(JavaLanguageVersion::asInt);

    Provider<ConsumableConfiguration> packagedApiElements = configurations.consumable(camelName(camelPrefix(featureName, PACKAGED_API_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("API elements for {0} packaged artifact '" + featureName + "' feature."));
      c.extendsFrom(api.get(), compileOnlyApi.get());
      getJvmPluginServices().configureAttributes(c, details -> details.apiUsage().library().asJar().withEmbeddedDependencies());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> {
        feature.getCapabilities().all(o::capability);
        o.artifact(getProject().getTasks().named(camelName(JAR_TASK_NAME)));
      });
    });

    Provider<ConsumableConfiguration> packagedRuntimeElements = configurations.consumable(camelName(camelPrefix(featureName, PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("Runtime elements for {0} packaged artifact '" + featureName + "' feature variant."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAttributes(c, details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(outgoing -> {
        feature.getCapabilities().all(outgoing::capability);
        outgoing.artifact(getProject().getTasks().named(camelName(JAR_TASK_NAME)));
      });
    });

    getProject().getComponents().named(JAVA_COMPONENT_NAME, AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(packagedApiElements.get(), variantDetails -> {
        variantDetails.mapToMavenScope("compile");
        variantDetails.mapToOptional();
      });
      java.addVariantsFromConfiguration(packagedRuntimeElements.get(), variantDetails -> {
        variantDetails.mapToMavenScope("runtime");
        variantDetails.mapToOptional();
      });
    });

    configurations.consumable(camelName(camelPrefix(featureName, UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("API elements for {0} unpackaged '" + featureName + "' feature contents."));
      c.extendsFrom(api.get(), compileOnlyApi.get());
      getJvmPluginServices().configureAttributes(c, details -> details.withExternalDependencies().apiUsage());
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(out -> feature.getCapabilities().all(out::capability));
    });

    configurations.consumable(camelName(camelPrefix(featureName, UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("Runtime elements for {0} unpackaged '" + featureName + "' feature contents."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withExternalDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion).attribute(Usage.USAGE_ATTRIBUTE, getProject().getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));
      c.outgoing(o -> feature.getCapabilities().all(o::capability));
    });
  }

  protected abstract String camelName(String base);

  protected abstract String kebabName(String base);

  protected abstract String description(String template);

  public static String camelPrefix(String prefix, String string) {
    if (prefix.isEmpty()) {
      return string;
    } else {
      return prefix + capitalize(string);
    }
  }

  interface JavadocInternal extends Javadoc {

    @Inject
    DependencyHandler getDependencies();

    @Override
    default void classpath(Object notation, Action<? super Dependency> action) {
      Dependency dependency = getDependencies().create(notation);
      action.execute(dependency);
      getClasspath().add(dependency);
    }

    @Override
    default void from(Object notation, Action<? super Dependency> action) {
      Dependency dependency = getDependencies().create(notation);
      action.execute(dependency);
      getArtifact().set(dependency);
    }
  }

  interface OptionalFeatureInternal extends OptionalFeature, CustomCapabilitiesInternal {}
}
