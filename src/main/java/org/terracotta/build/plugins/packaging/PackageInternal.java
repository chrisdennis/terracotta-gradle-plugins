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
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.jvm.internal.JvmEcosystemAttributesDetails;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.terracotta.build.plugins.JavaVersionPlugin;
import org.terracotta.build.plugins.PackagePlugin;

import javax.inject.Inject;
import java.util.Collections;

import static org.gradle.api.attributes.DocsType.JAVADOC;
import static org.gradle.api.attributes.DocsType.SOURCES;
import static org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE;
import static org.gradle.api.plugins.JavaBasePlugin.DOCUMENTATION_GROUP;
import static org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.JAR_TASK_NAME;
import static org.gradle.api.plugins.JavaPlugin.JAVADOC_ELEMENTS_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.JAVADOC_TASK_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME;
import static org.terracotta.build.PluginUtils.capitalize;
import static org.terracotta.build.Utils.coordinate;
import static org.terracotta.build.Utils.mapOf;
import static org.terracotta.build.plugins.PackagePlugin.EXPLODED_JAVA_RUNTIME;
import static org.terracotta.build.plugins.PackagePlugin.PACKAGE_COMPONENT_NAME;

@SuppressWarnings("UnstableApiUsage")
public abstract class PackageInternal implements Package {

  public static final String CONTENTS_CONFIGURATION_NAME = "contents";
  public static final String CONTENTS_API_CONFIGURATION_NAME = "contentsApi";
  public static final String CONTENTS_CLASSPATH_CONFIGURATION_NAME = "contentsClasspath";
  public static final String CONTENTS_SOURCES_CONFIGURATION_NAME = "contentsSources";
  public static final String CONTENTS_SOURCES_CLASSPATH_CONFIGURATION_NAME = "contentsSourcesClasspath";

  public static final String EXPLODED_API_ELEMENTS_CONFIGURATION_NAME = camelPrefix("exploded", API_ELEMENTS_CONFIGURATION_NAME);
  public static final String EXPLODED_RUNTIME_ELEMENTS_CONFIGURATION_NAME = camelPrefix("exploded", RUNTIME_ELEMENTS_CONFIGURATION_NAME);
  public static final String EXPLODED_RUNTIME_CLASSPATH_CONFIGURATION_NAME = camelPrefix("exploded", RUNTIME_CLASSPATH_CONFIGURATION_NAME);

  public static final String SOURCES_TASK_NAME = "sources";

  @Inject
  public abstract Project getProject();

  @Inject
  public abstract JvmPluginServices getJvmPluginServices();

  public abstract NamedDomainObjectContainer<OptionalFeatureInternal> getOptionalFeatures();

  public abstract DomainObjectSet<Capability> getCapabilities();

  @SuppressWarnings("UnstableApiUsage")
  public void create() {
    ConfigurationContainer configurations = getProject().getConfigurations();
    DependencyHandler dependencies = getProject().getDependencies();
    TaskContainer tasks = getProject().getTasks();
    Provider<Integer> javaCompileVersion = getProject().getExtensions().getByType(JavaVersionPlugin.JavaVersions.class).getCompileVersion().map(JavaLanguageVersion::asInt);

    /*
     * Retrieve the common dependency scopes that all packages inherit from
     */
    Provider<DependencyScopeConfiguration> commonContentsApi = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, CONTENTS_API_CONFIGURATION_NAME), DependencyScopeConfiguration.class);
    Provider<DependencyScopeConfiguration> commonContents = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, CONTENTS_CONFIGURATION_NAME), DependencyScopeConfiguration.class);
    Provider<DependencyScopeConfiguration> commonApi = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, API_CONFIGURATION_NAME), DependencyScopeConfiguration.class);
    Provider<DependencyScopeConfiguration> commonImplementation = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, IMPLEMENTATION_CONFIGURATION_NAME), DependencyScopeConfiguration.class);
    Provider<DependencyScopeConfiguration> commonCompileOnlyApi = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, COMPILE_ONLY_API_CONFIGURATION_NAME), DependencyScopeConfiguration.class);
    Provider<DependencyScopeConfiguration> commonRuntimeOnly = configurations.named(camelPrefix(PackagePlugin.COMMON_PREFIX, RUNTIME_ONLY_CONFIGURATION_NAME), DependencyScopeConfiguration.class);

    /*
     * Register the `contentsApi` and `contents` dependency scopes and wire them to the relative common scopes.
     */
    Provider<DependencyScopeConfiguration> contentsApi = configurations.dependencyScope(camelName(CONTENTS_API_CONFIGURATION_NAME), c -> c
        .extendsFrom(commonContentsApi.get())
        .setDescription(description("API dependencies for {0} package contents.")));
    Provider<DependencyScopeConfiguration> contents = configurations.dependencyScope(camelName(CONTENTS_CONFIGURATION_NAME), c -> c
        .extendsFrom(commonContents.get(), contentsApi.get())
        .setDescription(description("Implementation dependencies for {0} package contents."))
    );

    /*
     * Register the contentsClasspath. Once fully configured this will be the input to the `ShadowJar` task.
     */
    NamedDomainObjectProvider<ResolvableConfiguration> contentsClasspath = configurations.resolvable(camelName(CONTENTS_CLASSPATH_CONFIGURATION_NAME), c -> {
      c.extendsFrom(contents.get());
      c.setDescription(description("Classpath of {0} package contents."));
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      getJvmPluginServices().configureAsRuntimeClasspath(c);
    });

    /*
     * The variant metadata rules are not complex enough, nor applied uniformly enough to give us the "transient sources"
     * configuration that we need. Instead, we populate the contentSourcesElements configuration using the resolved
     * artifacts of the `contentsClasspath` contents configuration.
     */
    Provider<DependencyScopeConfiguration> contentsSources = configurations.dependencyScope(camelName(CONTENTS_SOURCES_CONFIGURATION_NAME), c -> c
        .setVisible(false)
        .withDependencies(config -> contentsClasspath.get().getIncoming().artifactView(view -> {}).getArtifacts()
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
    Provider<ResolvableConfiguration> contentsSourcesClasspath = configurations.resolvable(camelName(CONTENTS_SOURCES_CLASSPATH_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Source artifacts for {0} package contents."));
      c.extendsFrom(contentsSources.get());
      getJvmPluginServices().configureAttributes(c, details -> details.documentation(SOURCES));
      c.resolutionStrategy(resolutionStrategy -> ((ResolutionStrategyInternal) resolutionStrategy).assumeFluidDependencies());
    });

    /*
     * Form a virtual uber-sources file-tree. This is just files from the (lenient) resolution of the `contentsSourcesClasspath`
     * configuration mapped to individual file-trees and then summed together.
     */
    Provider<FileCollection> sourcesTree = contentsSourcesClasspath.flatMap(c -> c.getIncoming().artifactView(view -> view.lenient(true))
        .getFiles().getElements().map(files -> files.stream().map(file -> {
          if (file.getAsFile().isFile()) {
            return getProject().zipTree(file);
          } else {
            return getProject().fileTree(file);
          }
        }).reduce(FileTree::plus).orElse(getProject().files().getAsFileTree())));

    /*
     * Realize the virtual uber-sources file tree in to the build directory. We could eliminate this step and feed the
     * other tasks directly from the composite... but that will likely just realize the tree in to a temp location anyway.
     * Easier to just have it where we can easily poke at it ourselves.
     */
    TaskProvider<Sync> sources = tasks.register(camelName(SOURCES_TASK_NAME), Sync.class, sync -> {
      sync.setDescription(description("Collects the sources contributing to {0} packaged artifact."));
      sync.setGroup(DOCUMENTATION_GROUP);
      sync.from(sourcesTree, spec -> spec.exclude("META-INF/**"));
      sync.into(getProject().getLayout().getBuildDirectory().dir(kebabName("sources")));
    });

    /*
     * Register the 'user-visible' dependency scopes. Dependencies in these scopes (and their transitive graphs)
     * will not be packaged, but will instead be exposed as dependencies of the final package.
     */
    Provider<DependencyScopeConfiguration> api = configurations.dependencyScope(camelName(API_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonApi.get()).setDescription(description("API dependencies for {0} packaged artifact.")));
    NamedDomainObjectProvider<DependencyScopeConfiguration> implementation = configurations.dependencyScope(camelName(IMPLEMENTATION_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonImplementation.get(), api.get()).setDescription(description("Implementation dependencies for {0} packaged artifact.")));
    Provider<DependencyScopeConfiguration> compileOnlyApi = configurations.dependencyScope(camelName(COMPILE_ONLY_API_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonCompileOnlyApi.get()).setDescription(description("Compile-only API dependencies for {0} packaged artifact.")));
    Provider<DependencyScopeConfiguration> runtimeOnly = configurations.dependencyScope(camelName(RUNTIME_ONLY_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonRuntimeOnly.get()).setDescription(description("Runtime-only dependencies for {0} packaged artifact.")));

    /*
     * Consumable API configuration with 'exploded' external dependencies. This configuration gets selected by dependent
     * local projects for use at compile time. This means projects that depend on the packaged artifact can be safely
     * compiled using these elements before the actual jar is assembled.
     */
    configurations.consumable(camelName(EXPLODED_API_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("API elements for {0} unpackaged contents."));
      c.extendsFrom(api.get(), compileOnlyApi.get(), contentsApi.get());
      getJvmPluginServices().configureAsApiElements(c);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> getCapabilities().all(o::capability));
    });

    Usage explodedJavaRuntime = getProject().getObjects().named(Usage.class, EXPLODED_JAVA_RUNTIME);

    /*
     * Consumable runtime configuration with 'exploded' external dependencies. This configuration gets selected by
     * dependent local projects for use at runtime. This is used to ensure that when `project-a` depends on `project-b`
     * we don't package in to `project-a` things that are already packaged in `project-b`.
     */
    configurations.consumable(camelName(EXPLODED_RUNTIME_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Runtime elements for {0} unpackaged contents."));
      c.extendsFrom(implementation.get(), runtimeOnly.get(), contents.get());
      getJvmPluginServices().configureAsRuntimeElements(c);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
          .attribute(Usage.USAGE_ATTRIBUTE, explodedJavaRuntime));
      c.outgoing(o -> getCapabilities().all(o::capability));
    });

    /*
     * Resolvable runtime configuration with 'exploded' external dependencies. This configuration requests the
     * `exploded-java-runtime` usage attribute, which selects the explodedRuntimeElements consumable configuration when
     * it is available. When it is not available the `ExplodedJavaRuntimeCompatibility` attribute compatibility rule
     * allows fallback to the conventional `runtimeElements` configuration.
     */
    Provider<ResolvableConfiguration> explodedRuntimeClasspath =
        configurations.resolvable(camelName(EXPLODED_RUNTIME_CLASSPATH_CONFIGURATION_NAME), c -> {
          c.setDescription(description("Runtime classpath of {0} unpackaged contents."));
          c.extendsFrom(implementation.get(), runtimeOnly.get());
          getJvmPluginServices().configureAsRuntimeClasspath(c);
          c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
              .attribute(Usage.USAGE_ATTRIBUTE, explodedJavaRuntime));
        });
    /*
     * Exclude things on the exploded runtime classpath from the package contents.
     */
    contentsClasspath.configure(c -> c.getIncoming().beforeResolve(config -> {
      explodedRuntimeClasspath.get().getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
        ModuleVersionIdentifier identifier = resolvedArtifact.getModuleVersion().getId();
        c.exclude(mapOf(String.class, String.class, "group", identifier.getGroup(), "module", identifier.getName()));
      });
    }));


    /*
     * Resolvable runtime configuration with default (potentially embedded) dependencies.
     */
    Provider<ResolvableConfiguration> runtimeClasspath = configurations.resolvable(camelName(RUNTIME_CLASSPATH_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Runtime classpath of {0} packaged artifact."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAsRuntimeClasspath(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withEmbeddedDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    });


    /*
     * Create the main packaging task. Shading the contentsClasspath resolution, and generating an associated OSGi manifest
     */
    TaskProvider<ShadowJar> shadowJar = tasks.register(camelName(JAR_TASK_NAME), ShadowJar.class, shadow -> {
      shadow.setDescription(description("Assembles a jar archive containing {0} packaged classes."));
      shadow.setGroup(BasePlugin.BUILD_GROUP);

      shadow.setConfigurations(Collections.singletonList(contentsClasspath.get()));
      shadow.getArchiveClassifier().set(kebabName(""));
      shadow.mergeServiceFiles();

      shadow.exclude("META-INF/MANIFEST.MF");

      shadow.getExtensions().configure(OsgiManifestJarExtension.class, osgi -> {
        osgi.getClasspath().from(runtimeClasspath);
        osgi.getSources().from(sources);

        osgi.instruction(Constants.BUNDLE_VERSION, new MavenVersion(getProject().getVersion().toString()).getOSGiVersion().toString());
      });
    });


    /*
     * Register the normal `apiElements` configuration, composed of the api dependency scopes, along with the packaged jar, and marked as embedded.
     */
    Provider<ConsumableConfiguration> apiElements = configurations.consumable(camelName(API_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("API elements for {0} packaged artifact."));
      c.extendsFrom(api.get(), compileOnlyApi.get());
      getJvmPluginServices().configureAsApiElements(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withEmbeddedDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(shadowJar);
      });
    });

    /*
     * Register the normal `runtimeElements` configuration, composed of the api dependency scopes, along with the packaged jar, and marked as embedded.
     */
    Provider<ConsumableConfiguration> runtimeElements = configurations.consumable(camelName(RUNTIME_ELEMENTS_CONFIGURATION_NAME), c -> {
      c.setDescription(description("Runtime elements for {0} packaged artifact."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAsRuntimeElements(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withEmbeddedDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(shadowJar);
      });
    });

    /*
     * Tie the 'normal' consumable configurations to the `java` software component so that they can be published.
     */
    getProject().getComponents().named(PACKAGE_COMPONENT_NAME, AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(apiElements.get(), variantDetails -> variantDetails.mapToMavenScope("compile"));
      java.addVariantsFromConfiguration(runtimeElements.get(), variantDetails -> variantDetails.mapToMavenScope("runtime"));
    });

    getOptionalFeatures().all(this::createOptionalFeature);
  }

  private void createOptionalFeature(OptionalFeatureInternal feature) {
    String featureName = feature.getName();

    ConfigurationContainer configurations = getProject().getConfigurations();

    /*
     * Register the 'user-visible' dependency scopes for the optional feature bucket
     */
    Provider<DependencyScopeConfiguration> api = configurations.dependencyScope(camelName(camelPrefix(featureName, API_CONFIGURATION_NAME)),
        c -> c.setDescription(description("API dependencies for {0} packaged artifact '" + featureName + "' feature.")));
    Provider<DependencyScopeConfiguration> implementation = configurations.dependencyScope(camelName(camelPrefix(featureName, IMPLEMENTATION_CONFIGURATION_NAME)),
        c -> c.extendsFrom(api.get()).setDescription(description("Implementation dependencies for {0} packaged artifact '" + featureName + "' feature.")));
    Provider<DependencyScopeConfiguration> compileOnlyApi = configurations.dependencyScope(camelName(camelPrefix(featureName, COMPILE_ONLY_API_CONFIGURATION_NAME)),
        c -> c.setDescription(description("Compile-only dependencies for {0} packaged artifact '" + featureName + "' feature.")));
    Provider<DependencyScopeConfiguration> runtimeOnly = configurations.dependencyScope(camelName(camelPrefix(featureName, RUNTIME_ONLY_CONFIGURATION_NAME)),
        c -> c.setDescription(description("Runtime-only dependencies for {0} packaged artifact '" + featureName + "' feature.")));


    Provider<Integer> javaCompileVersion = getProject().getExtensions().getByType(JavaVersionPlugin.JavaVersions.class).getCompileVersion().map(JavaLanguageVersion::asInt);

    Provider<ConsumableConfiguration> apiElements = configurations.consumable(camelName(camelPrefix(featureName, API_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("API elements for {0} packaged artifact '" + featureName + "' feature."));
      c.extendsFrom(api.get(), compileOnlyApi.get());
      getJvmPluginServices().configureAsApiElements(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withEmbeddedDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(o -> {
        feature.getCapabilities().all(o::capability);
        o.artifact(getProject().getTasks().named(camelName(JAR_TASK_NAME)));
      });
    });

    Provider<ConsumableConfiguration> runtimeElements = configurations.consumable(camelName(camelPrefix(featureName, RUNTIME_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("Runtime elements for {0} packaged artifact '" + featureName + "' feature variant."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAsRuntimeElements(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withEmbeddedDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(outgoing -> {
        feature.getCapabilities().all(outgoing::capability);
        outgoing.artifact(getProject().getTasks().named(camelName(JAR_TASK_NAME)));
      });
    });

    getProject().getComponents().named(PACKAGE_COMPONENT_NAME, AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(apiElements.get(), variantDetails -> {
        variantDetails.mapToMavenScope("compile");
        variantDetails.mapToOptional();
      });
      java.addVariantsFromConfiguration(runtimeElements.get(), variantDetails -> {
        variantDetails.mapToMavenScope("runtime");
        variantDetails.mapToOptional();
      });
    });

    configurations.consumable(camelName(camelPrefix(featureName, EXPLODED_API_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("API elements for {0} unpackaged '" + featureName + "' feature contents."));
      c.extendsFrom(api.get(), compileOnlyApi.get());
      getJvmPluginServices().configureAsApiElements(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withExternalDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
      c.outgoing(out -> feature.getCapabilities().all(out::capability));
    });

    Usage explodedJavaRuntime = getProject().getObjects().named(Usage.class, EXPLODED_JAVA_RUNTIME);

    configurations.consumable(camelName(camelPrefix(featureName, EXPLODED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)), c -> {
      c.setDescription(description("Runtime elements for {0} unpackaged '" + featureName + "' feature contents."));
      c.extendsFrom(implementation.get(), runtimeOnly.get());
      getJvmPluginServices().configureAsRuntimeElements(c);
      getJvmPluginServices().configureAttributes(c, JvmEcosystemAttributesDetails::withExternalDependencies);
      c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion).attribute(Usage.USAGE_ATTRIBUTE, explodedJavaRuntime));
      c.outgoing(o -> feature.getCapabilities().all(o::capability));
    });

    Provider<ResolvableConfiguration> explodedRuntimeClasspath =
        configurations.resolvable(camelName(camelPrefix(featureName, EXPLODED_RUNTIME_CLASSPATH_CONFIGURATION_NAME)), c -> {
          c.setDescription(description("Runtime classpath of {0} unpackaged '" + featureName + "' contents."));
          c.extendsFrom(implementation.get(), runtimeOnly.get());
          getJvmPluginServices().configureAsRuntimeClasspath(c);
          c.attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
              .attribute(Usage.USAGE_ATTRIBUTE, explodedJavaRuntime));
        });


    configurations.named(camelName(CONTENTS_CLASSPATH_CONFIGURATION_NAME), ResolvableConfiguration.class).configure(c -> c.getIncoming().beforeResolve(config -> {
      explodedRuntimeClasspath.get().getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
        ModuleVersionIdentifier identifier = resolvedArtifact.getModuleVersion().getId();
        c.exclude(mapOf(String.class, String.class, "group", identifier.getGroup(), "module", identifier.getName()));
      });
    }));
  }

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

    getProject().getComponents().named(PACKAGE_COMPONENT_NAME, AdhocComponentWithVariants.class,
            java -> java.addVariantsFromConfiguration(sourcesElements.get(), variantDetails -> {}));
  }

  @Override
  public void withJavadocJar(Action<Javadoc> action) {
    Javadoc javadocSettings = getProject().getObjects().newInstance(JavadocInternal.class);
    action.execute(javadocSettings);

    ConfigurationContainer configurations = getProject().getConfigurations();
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

      getProject().getComponents().named(PACKAGE_COMPONENT_NAME, AdhocComponentWithVariants.class,
              java -> java.addVariantsFromConfiguration(javadocElements.get(), variantDetails -> {}));
    } else {
      Provider<DependencyScopeConfiguration> additionalJavadoc = configurations.dependencyScope(camelName("additionalJavadoc"), c -> {
        c.setDescription("Additional javadoc generation dependencies.");
        javadocSettings.getClasspath().all(notation -> c.getDependencies().add(notation));
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

      getProject().getComponents().named(PACKAGE_COMPONENT_NAME, AdhocComponentWithVariants.class,
              java -> java.addVariantsFromConfiguration(javadocElements.get(), variantDetails -> {}));
    }
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

  public interface OptionalFeatureInternal extends OptionalFeature, CustomCapabilitiesInternal {}
}
