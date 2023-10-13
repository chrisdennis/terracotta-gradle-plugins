package org.terracotta.build.plugins.packaging;

import aQute.bnd.osgi.Constants;
import aQute.bnd.version.MavenVersion;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.HasConfigurableAttributes;
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
      jar.getArchiveClassifier().set(snakeName("sources"));
    });

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();
    Configuration sourcesElements = configureAttributes(configurations.consumable(camelName(SOURCES_ELEMENTS_CONFIGURATION_NAME))
                    .setDescription(description("Sources elements for {0} packaged artifact.")),
            details -> details.runtimeUsage().withExternalDependencies().documentation(SOURCES));
    sourcesElements.outgoing(o -> {
      getCapabilities().all(o::capability);
      o.artifact(sourcesJar);
    });

    getProject().getComponents().named(JAVA_COMPONENT_NAME, AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(sourcesElements, variantDetails -> {});
    });

    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
      task.dependsOn(sourcesJar);
    });
  }

  @Override
  public void withJavadocJar(Action<Javadoc> action) {
    Javadoc javadocSettings = getProject().getObjects().newInstance(JavadocInternal.class);
    action.execute(javadocSettings);

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();
    TaskContainer tasks = getProject().getTasks();

    if (javadocSettings.getArtifact().isPresent()) {
      Configuration javadoc = configurations.bucket(camelName("inheritedJavadoc"))
              .setDescription(description("Dependencies for {0} inherited javadoc."));
      javadoc.getDependencies().add(javadocSettings.getArtifact().get());

      Configuration javadocJars = configureAttributes(configurations.resolvable(camelName("inheritedJavadocJars"))
              .setDescription("Inherited javadoc files for merging.")
              .extendsFrom(javadoc), details -> details.documentation(JAVADOC).asJar());

      TaskProvider<Jar> javadocJar = tasks.register(camelName("javadocJar"), Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the inherited javadoc.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadocJars.getElements().map(locations -> locations.stream().map(getProject()::zipTree).toArray())).exclude("LICENSE");
        jar.getArchiveClassifier().set(snakeName("javadoc"));
      });

      Configuration javadocElements = configureAttributes(configurations.consumable(camelName(JAVADOC_ELEMENTS_CONFIGURATION_NAME)),
              details -> details.runtimeUsage().withExternalDependencies().documentation(JAVADOC));
      javadocElements.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(javadocJar);
      });

      getProject().getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(javadocElements, variantDetails -> {});
      });

      tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(javadocJar);
      });

    } else {
      Configuration javadocClasspath = configurations.bucket(camelName("javadocClasspath"))
              .setDescription("Additional javadoc generation dependencies.");

      javadocSettings.getClasspath().get().forEach(notation -> {
        javadocClasspath.getDependencies().add(getProject().getDependencies().create(notation));
      });

      Configuration additionalJavadocClasspath = configurations.resolvable(camelName("additionalJavadocClasspath"))
              .setDescription(description("Additional classpath for {0} javadoc generation.")).extendsFrom(javadocClasspath);
      getJvmPluginServices().configureAsRuntimeClasspath(additionalJavadocClasspath);

      TaskProvider<org.gradle.api.tasks.javadoc.Javadoc> javadoc = tasks.register(camelName(JAVADOC_TASK_NAME), org.gradle.api.tasks.javadoc.Javadoc.class, task -> {
        task.setDescription("Generates Javadoc API documentation for {0} packaged source code.");
        task.setGroup(DOCUMENTATION_GROUP);
        task.setTitle(getProject().getName() + " " + getProject().getVersion() + " API");
        task.source(tasks.named(camelName(SOURCES_TASK_NAME)));
        task.include("**/*.java");
        task.getModularity().getInferModulePath().set(false);
        task.setClasspath(configurations.getByName(camelName(CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME))
                .plus(configurations.getByName(camelName(MAXIMAL_UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME)))
                .plus(additionalJavadocClasspath));
        task.setDestinationDir(new File(getProject().getBuildDir(), snakeName(JAVADOC_TASK_NAME)));
      });
      TaskProvider<Jar> javadocJar = tasks.register(camelName("javadocJar"), Jar.class, jar -> {
        jar.setDescription(description("Assembles a jar archive containing {0} packaged javadoc."));
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadoc);
        jar.getArchiveClassifier().set(snakeName("javadoc"));
      });

      Configuration javadocElements = configureAttributes(configurations.consumable(camelName(JAVADOC_ELEMENTS_CONFIGURATION_NAME))
                      .setDescription(description("Javadoc elements for {0} packaged artifact.")),
              details -> details.runtimeUsage().withExternalDependencies().documentation(JAVADOC));
      javadocElements.outgoing(o -> {
        getCapabilities().all(o::capability);
        o.artifact(javadocJar);
      });

      getProject().getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(javadocElements, variantDetails -> {
        });
      });

      tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(javadocJar);
      });
    }
  }

  public void create() {
    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();
    DependencyHandler dependencies = getProject().getDependencies();
    TaskContainer tasks = getProject().getTasks();
    Provider<Integer> javaCompileVersion = getProject().getExtensions().getByType(JavaVersionPlugin.JavaVersions.class).getCompileVersion().map(JavaLanguageVersion::asInt);

    Configuration contentsApi = configurations.bucket(camelName(CONTENTS_API_CONFIGURATION_NAME))
            .setDescription(description("API dependencies for {0} package contents."));
    Configuration contents = configurations.bucket(camelName(CONTENTS_CONFIGURATION_NAME)).extendsFrom(contentsApi)
            .setDescription(description("Implementation dependencies for {0} package contents."));

    Configuration contentsRuntimeClasspath = configurations.resolvable(camelName(CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME)).extendsFrom(contents)
            .setDescription(description("Runtime classpath of {0} package contents."))
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    getJvmPluginServices().configureAsRuntimeClasspath(contentsRuntimeClasspath);

    /*
     * The variant metadata rules are not complex enough, nor applied uniformly enough to give us the "transient sources"
     * configuration that we need. Instead, we populate the contentSourcesElements configuration using the resolved
     * artifacts of the shadow contents configuration.
     */
    Configuration contentsSources = configurations.bucket(camelName(CONTENTS_SOURCES_CONFIGURATION_NAME)).setVisible(false)
            .withDependencies(config -> contentsRuntimeClasspath.getIncoming().artifactView(view -> {
            }).getArtifacts().getResolvedArtifacts().get().stream().map(ResolvedArtifactResult::getVariant).forEach(variant -> {
              ComponentIdentifier id = variant.getOwner();
              Dependency dependency;
              if (id instanceof ProjectComponentIdentifier) {
                ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) id;
                dependency = dependencies.project(coordinate(projectId.getProjectPath()));
              } else if (id instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier moduleId = (ModuleComponentIdentifier) id;
                dependency = dependencies.create(mapOf(
                        "group", moduleId.getGroup(),
                        "name", moduleId.getModule(),
                        "version", moduleId.getVersion()));
              } else {
                throw new GradleException("Unsupported component identifier type: " + id);
              }
              ((ModuleDependency) dependency).capabilities(o -> variant.getCapabilities().forEach(o::requireCapability));
              config.add(dependency);
            }));
    Configuration contentsSourcesElements = configureAttributes(configurations.resolvable(camelName(CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME))
            .setDescription(description("Source artifacts for {0} package contents.")).extendsFrom(contentsSources), details -> details.documentation(SOURCES))
            .resolutionStrategy(resolutionStrategy -> ((ResolutionStrategyInternal) resolutionStrategy).assumeFluidDependencies());

    /*
     * The above mess should (I think) be replaceable by an artifactView like the following... but currently this fails
     * to use the dependency capabilities when re-resolving the variants.
     */
    //FileCollection sourceFiles = contentsRuntimeClasspath.getIncoming()
    //        .artifactView(view -> configureAttributes(view.withVariantReselection().lenient(true), details -> details.documentation(SOURCES)))
    //        .getFiles();


    Provider<FileCollection> sourcesTree = getProject().provider(() -> contentsSourcesElements.getResolvedConfiguration().getLenientConfiguration().getAllModuleDependencies().stream().flatMap(d -> d.getModuleArtifacts().stream())
            .map(artifact -> {
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
      sync.into(getProject().getLayout().getBuildDirectory().dir(snakeName("sources")));
    });

    Configuration api = configurations.bucket(camelName(API_CONFIGURATION_NAME))
            .setDescription(description("API dependencies for {0} packaged artifact."));
    Configuration implementation = configurations.bucket(camelName(IMPLEMENTATION_CONFIGURATION_NAME)).extendsFrom(api)
            .setDescription(description("Implementation dependencies for {0} packaged artifact."));
    Configuration compileOnlyApi = configurations.bucket(camelName(COMPILE_ONLY_API_CONFIGURATION_NAME))
            .setDescription(description("Compile-only API dependencies for {0} packaged artifact."));
    Configuration runtimeOnly = configurations.bucket(camelName(RUNTIME_ONLY_CONFIGURATION_NAME))
            .setDescription(description("Runtime-only dependencies for {0} packaged artifact."));
    Configuration provided = configurations.bucket(camelName(PROVIDED_CONFIGURATION_NAME))
            .setDescription(description("'Provided' API dependencies for {0} packaged artifact."));

    configureAttributes(configurations.consumable(camelName(UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME))
            .setDescription(description("API elements for {0} unpackaged contents."))
            .extendsFrom(api, compileOnlyApi, contentsApi), details -> details.apiUsage().library().asJar().withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion))
            .outgoing(o -> getCapabilities().all(o::capability));

    configureAttributes(configurations.consumable(camelName(UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME))
            .setDescription(description("Runtime elements for {0} unpackaged contents."))
            .extendsFrom(implementation, runtimeOnly, contents), details -> details.library().asJar().withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
                    .attribute(Usage.USAGE_ATTRIBUTE, getProject().getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)))
            .outgoing(o -> getCapabilities().all(o::capability));

    Configuration maximalUnpackagedRuntimeClasspath = configureAttributes(configurations.resolvable(camelName(MAXIMAL_UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME))
            .setDescription(description("Maximal (incl all optional features) runtime classpath of {0} unpackaged contents."))
            .extendsFrom(implementation, runtimeOnly), details -> details.withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
                    .attribute(Usage.USAGE_ATTRIBUTE, getProject().getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));

    TaskProvider<ShadowJar> shadowJar = tasks.register(camelName(JAR_TASK_NAME), ShadowJar.class, shadow -> {
      shadow.setDescription(description("Assembles a jar archive containing {0} packaged classes."));
      shadow.setGroup(BasePlugin.BUILD_GROUP);

      shadow.setConfigurations(Collections.singletonList(contentsRuntimeClasspath));
      shadow.getArchiveClassifier().set(snakeName(""));
      shadow.mergeServiceFiles();

      shadow.exclude("META-INF/MANIFEST.MF");
    });

    configurations.named(DEFAULT_CONFIGURATION).configure(c -> c.outgoing(o -> o.artifact(shadowJar)));

    Configuration packagedApiElements = configureAttributes(configurations.consumable(camelName(PACKAGED_API_ELEMENTS_CONFIGURATION_NAME))
            .setDescription(description("API elements for {0} packaged artifact."))
            .extendsFrom(api, compileOnlyApi), details -> details.apiUsage().library().asJar().withEmbeddedDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    packagedApiElements.outgoing(o -> {
      getCapabilities().all(o::capability);
      o.artifact(shadowJar);
    } );

    Configuration packagedRuntimeElements = configureAttributes(configurations.consumable(camelName(PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME))
            .setDescription(description("Runtime elements for {0} packaged artifact."))
            .extendsFrom(implementation, runtimeOnly), details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    packagedRuntimeElements.outgoing(o -> {
      getCapabilities().all(o::capability);
      o.artifact(shadowJar);
    });

    Configuration packagedRuntimeClasspath = configureAttributes(configurations.resolvable(camelName(PACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME))
            .setDescription(description("Runtime classpath of {0} packaged artifact."))
            .extendsFrom(implementation, runtimeOnly), details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));

    contentsRuntimeClasspath.getIncoming().beforeResolve(config -> {
      maximalUnpackagedRuntimeClasspath.getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
        ModuleVersionIdentifier identifier = resolvedArtifact.getModuleVersion().getId();
        contentsRuntimeClasspath.exclude(mapOf(String.class, String.class, "group", identifier.getGroup(), "module", identifier.getName()));
      });
    });

    shadowJar.configure(shadow -> {
      OsgiManifestJarExtension osgi = shadow.getExtensions().findByType(OsgiManifestJarExtension.class);
      osgi.getClasspath().from(packagedRuntimeClasspath);
      osgi.getSources().from(sources);

      osgi.instruction(Constants.BUNDLE_VERSION, new MavenVersion(getProject().getVersion().toString()).getOSGiVersion().toString());
    });

    getProject().getComponents().named("java", AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(packagedApiElements, variantDetails -> variantDetails.mapToMavenScope("compile"));
      java.addVariantsFromConfiguration(packagedRuntimeElements, variantDetails -> variantDetails.mapToMavenScope("runtime"));
    });

    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
      task.dependsOn(shadowJar);
    });

    provided.getDependencies().configureEach(dependency -> {
      implementation.getDependencyConstraints().add(DefaultDependencyConstraint.strictly(dependency.getGroup(), dependency.getName(), dependency.getVersion()));
    });

    getOptionalFeatures().all(this::createOptionalFeature);
  }

  public void createOptionalFeature(OptionalFeatureInternal feature) {
    String featureName = feature.getName();

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();

    Configuration api = configurations.bucket(camelName(camelPrefix(featureName, API_CONFIGURATION_NAME)))
            .setDescription(description("API dependencies for {0} packaged artifact '" + featureName + "' feature."));
    Configuration implementation = configurations.bucket(camelName(camelPrefix(featureName, IMPLEMENTATION_CONFIGURATION_NAME))).extendsFrom(api)
            .setDescription(description("Implementation dependencies for {0} packaged artifact '" + featureName + "' feature."));
    Configuration compileOnlyApi = configurations.bucket(camelName(camelPrefix(featureName, COMPILE_ONLY_API_CONFIGURATION_NAME)))
            .setDescription(description("Compile-only dependencies for {0} packaged artifact '" + featureName + "' feature."));
    Configuration runtimeOnly = configurations.bucket(camelName(camelPrefix(featureName, RUNTIME_ONLY_CONFIGURATION_NAME)))
            .setDescription(description("Runtime-only dependencies for {0} packaged artifact '" + featureName + "' feature."));


    configurations.named(camelName(MAXIMAL_UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME)).configure(c -> {
      c.extendsFrom(implementation, runtimeOnly);
    });

    Provider<Integer> javaCompileVersion = getProject().getExtensions().getByType(JavaVersionPlugin.JavaVersions.class).getCompileVersion().map(JavaLanguageVersion::asInt);

    Configuration packagedApiElements = configureAttributes(configurations.consumable(camelName(camelPrefix(featureName, PACKAGED_API_ELEMENTS_CONFIGURATION_NAME)))
            .setDescription(description("API elements for {0} packaged artifact '" + featureName + "' feature."))
            .extendsFrom(api, compileOnlyApi), details -> details.apiUsage().library().asJar().withEmbeddedDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    packagedApiElements.outgoing(o -> {
      feature.getCapabilities().all(o::capability);
      o.artifact(getProject().getTasks().named(camelName(JAR_TASK_NAME)));
    });

    Configuration packagedRuntimeElements = configureAttributes(configurations.consumable(camelName(camelPrefix(featureName, PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)))
            .setDescription(description("Runtime elements for {0} packaged artifact '" + featureName + "' feature variant."))
            .extendsFrom(implementation, runtimeOnly), details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    packagedRuntimeElements.outgoing(outgoing -> {
      feature.getCapabilities().all(outgoing::capability);
      outgoing.artifact(getProject().getTasks().named(camelName(JAR_TASK_NAME)));
    });

    getProject().getComponents().named(JAVA_COMPONENT_NAME, AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(packagedApiElements, variantDetails -> {
        variantDetails.mapToMavenScope("compile");
        variantDetails.mapToOptional();
      });
      java.addVariantsFromConfiguration(packagedRuntimeElements, variantDetails -> {
        variantDetails.mapToMavenScope("runtime");
        variantDetails.mapToOptional();
      });
    });

    Configuration unpackagedApiElements = configureAttributes(configurations.consumable(camelName(camelPrefix(featureName, UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME)))
            .setDescription(description("API elements for {0} unpackaged '" + featureName + "' feature contents."))
            .extendsFrom(api, compileOnlyApi), details -> details.withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion));
    unpackagedApiElements.outgoing(out -> feature.getCapabilities().all(out::capability));

    Configuration unpackagedRuntimeElements = configureAttributes(configurations.consumable(camelName(camelPrefix(featureName, UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)))
            .setDescription(description("Runtime elements for {0} unpackaged '" + featureName + "' feature contents."))
            .extendsFrom(implementation, runtimeOnly), details -> details.withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaCompileVersion)
                    .attribute(Usage.USAGE_ATTRIBUTE, getProject().getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));
    unpackagedRuntimeElements.outgoing(o -> feature.getCapabilities().all(o::capability));
  }

  protected abstract String camelName(String base);

  protected abstract String snakeName(String base);

  protected abstract String description(String template);

  private <T extends HasConfigurableAttributes<T>> T configureAttributes(T attributed, Action<? super JvmEcosystemAttributesDetails> details) {
    getJvmPluginServices().configureAttributes(attributed, details);
    return attributed;
  }

  protected static String camelPrefix(String prefix, String string) {
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
