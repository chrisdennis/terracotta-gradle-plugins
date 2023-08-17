package org.terracotta.build.plugins;

import aQute.bnd.osgi.Builder;
import aQute.bnd.osgi.Constants;
import aQute.bnd.version.MavenVersion;
import aQute.service.reporter.Report;
import com.github.jengelman.gradle.plugins.shadow.ShadowStats;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocateClassContext;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.attributes.HasConfigurableAttributes;
import org.gradle.api.internal.artifacts.JavaEcosystemSupport;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.plugins.jvm.internal.JvmEcosystemAttributesDetails;
import org.terracotta.build.Utils;
import org.terracotta.build.plugins.JavaVersionPlugin.JavaVersions;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.ConfigurationVariantDetails;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.internal.resolve.ArtifactResolveException;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.util.internal.ClosureBackedAction;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.gradle.api.artifacts.Dependency.DEFAULT_CONFIGURATION;
import static org.gradle.api.attributes.DocsType.JAVADOC;
import static org.gradle.api.attributes.DocsType.SOURCES;
import static org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE;
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
import static org.terracotta.build.Utils.mapOf;

/**
 * EhDistribute
 */
public class PackagePlugin implements Plugin<Project> {

  public static final String UNPACKAGED_JAVA_RUNTIME = "unpackaged-java-runtime";

  public static final String CONTENTS_CONFIGURATION_NAME = "contents";
  public static final String CONTENTS_API_CONFIGURATION_NAME = "contentsApi";
  public static final String CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "contentsRuntimeClasspath";
  public static final String CONTENTS_SOURCES_CONFIGURATION_NAME = "contentsSources";
  public static final String CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME = "contentsSourcesElements";

  public static final String PROVIDED_CONFIGURATION_NAME = "provided";

  public static final String UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME = "unpackagedApiElements";
  public static final String UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME = "unpackagedRuntimeElements";
  public static final String UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "unpackagedRuntimeClasspath";

  public static final String PACKAGED_API_ELEMENTS_CONFIGURATION_NAME = "packagedApiElements";
  public static final String PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME = "packagedRuntimeElements";
  public static final String PACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "packagedRuntimeClasspath";

  public static final String SOURCES_TASK_NAME = "sources";

  private static final Pattern OSGI_EXPORT_PATTERN = Pattern.compile("([^;,]+((?:;[^,:=]+:?=\"[^\"]+\")*))(?:,|$)");

  private final JvmPluginServices jvmPluginServices;

  @Inject
  public PackagePlugin(JvmPluginServices jvmPluginServices) {
    this.jvmPluginServices = jvmPluginServices;
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BasePlugin.class);

    ServiceRegistry projectServices = ((ProjectInternal) project).getServices();
    SoftwareComponentFactory softwareComponentFactory = projectServices.get(SoftwareComponentFactory.class);
    AdhocComponentWithVariants javaComponent = softwareComponentFactory.adhoc("java");
    project.getComponents().add(javaComponent);

    createDefaultPackage(project);

    project.getPlugins().withType(MavenPublishPlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(PublishingExtension.class, publishing -> {
        publishing.getPublications().register("mavenJava", MavenPublication.class, mavenPublication -> {
          mavenPublication.from(javaComponent);
        });
      });
    });

    project.getExtensions().add(PackageProjectExtension.class, "packaging", new PackageProjectExtension(project));
  }

  private void createDefaultPackage(Project project) {
    project.getPlugins().apply(JavaVersionPlugin.class);

    JavaVersions javaVersions = project.getExtensions().getByType(JavaVersions.class);

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) project.getConfigurations();

    Configuration contentsApi = configurations.bucket(CONTENTS_API_CONFIGURATION_NAME)
            .setDescription("API dependencies for the package contents.");
    Configuration contents = configurations.bucket(CONTENTS_CONFIGURATION_NAME).extendsFrom(contentsApi)
            .setDescription("Implementation dependencies for the package contents.");

    Configuration contentsRuntimeClasspath = configurations.resolvable(CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME).extendsFrom(contents)
            .setDescription("Runtime classpath of the package contents.")
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
    jvmPluginServices.configureAsRuntimeClasspath(contentsRuntimeClasspath);

    /*
     * The variant metadata rules are not complex enough, nor applied uniformly enough to give us the "transient sources"
     * configuration that we need. Instead, we populate the contentSourcesElements configuration using the resolved
     * artifacts of the shadow contents configuration.
     */
    Configuration contentsSources = configurations.bucket(CONTENTS_SOURCES_CONFIGURATION_NAME).setVisible(false)
            .withDependencies(config -> contentsRuntimeClasspath.getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
              ComponentIdentifier id = resolvedArtifact.getId().getComponentIdentifier();
              if (id instanceof ProjectComponentIdentifier) {
                ProjectComponentIdentifier projectId = (ProjectComponentIdentifier) id;
                config.add(project.getDependencies().create(project.project(projectId.getProjectPath())));
              } else if (id instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier moduleId = (ModuleComponentIdentifier) id;
                config.add(project.getDependencies().create(Utils.mapOf(
                        "group", moduleId.getGroup(),
                        "name", moduleId.getModule(),
                        "version", moduleId.getVersion())));
              } else {
                throw new GradleException("Unsupported component identifier type: " + id);
              }
            }));
    Configuration contentsSourcesElements = configureAttributes(configurations.resolvable(CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME)
            .setDescription("Source artifacts for the package contents.").extendsFrom(contentsSources), details -> details.documentation(SOURCES))
            .resolutionStrategy(resolutionStrategy -> ((ResolutionStrategyInternal) resolutionStrategy).assumeFluidDependencies());


    Provider<FileCollection> sourcesTree = project.provider(() -> contentsSourcesElements.getResolvedConfiguration().getLenientConfiguration().getAllModuleDependencies().stream().flatMap(d -> d.getModuleArtifacts().stream())
            .map(artifact -> {
              try {
                return Optional.of(artifact.getFile());
              } catch (ArtifactResolveException e) {
                return Optional.<File>empty();
              }
            }).filter(Optional::isPresent).map(Optional::get).distinct().map(file -> {
              if (file.isFile()) {
                return project.zipTree(file);
              } else {
                return project.fileTree(file);
              }
            }).reduce(FileTree::plus).orElse(project.files().getAsFileTree()));

    TaskProvider<Sync> sources = project.getTasks().register(SOURCES_TASK_NAME, Sync.class, sync -> {
      sync.setDescription("Collects the sources contributing to this packaged artifact.");
      sync.setGroup(DOCUMENTATION_GROUP);
      sync.dependsOn(contentsSourcesElements);
      sync.from(sourcesTree, spec -> spec.exclude("META-INF/**"));
      sync.into(project.getLayout().getBuildDirectory().dir("sources"));
    });

    Configuration api = configurations.bucket(API_CONFIGURATION_NAME)
            .setDescription("API dependencies for the packaged artifact.");
    Configuration implementation = configurations.bucket(IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(api)
            .setDescription("Implementation dependencies for the packaged artifact.");
    Configuration compileOnlyApi = configurations.bucket(COMPILE_ONLY_API_CONFIGURATION_NAME)
            .setDescription("Compile-only API dependencies for packaged artifact.");
    Configuration runtimeOnly = configurations.bucket(RUNTIME_ONLY_CONFIGURATION_NAME)
            .setDescription("Runtime-only dependencies for the packaged artifact.");
    Configuration provided = configurations.bucket(PROVIDED_CONFIGURATION_NAME)
            .setDescription("'Provided' API dependencies for the packaged artifact.");

    configureAttributes(configurations.consumable(UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME)
            .setDescription("API elements for the unpackaged contents.")
            .extendsFrom(api, compileOnlyApi, contentsApi), details -> details.apiUsage().library().asJar().withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));

    configureAttributes(configurations.consumable(UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)
            .setDescription("Runtime elements for the unpackaged contents.")
            .extendsFrom(implementation, runtimeOnly, contents), details -> details.library().asJar().withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt))
                    .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));

    Configuration unpackagedRuntimeClasspath = configureAttributes(configurations.resolvable(UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME)
            .setDescription("Runtime classpath of the unpackaged contents.")
            .extendsFrom(implementation, runtimeOnly), details -> details.withExternalDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt))
                    .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));

    TaskProvider<ShadowJar> shadowJar = project.getTasks().register(JAR_TASK_NAME, ShadowJar.class, shadow -> {
      shadow.setDescription("Assembles a jar archive containing the packaged classes.");
      shadow.setGroup(BasePlugin.BUILD_GROUP);

      shadow.setConfigurations(Collections.singletonList(contentsRuntimeClasspath));

      shadow.mergeServiceFiles();

      shadow.exclude("META-INF/MANIFEST.MF");
    });

    configurations.named(DEFAULT_CONFIGURATION).configure(c -> c.outgoing(o -> o.artifact(shadowJar)));

    Configuration packagedApiElements = configureAttributes(configurations.consumable(PACKAGED_API_ELEMENTS_CONFIGURATION_NAME)
            .setDescription("API elements for the packaged artifact.")
            .extendsFrom(api, compileOnlyApi), details -> details.apiUsage().library().asJar().withEmbeddedDependencies())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
    packagedApiElements.outgoing(o -> o.artifact(shadowJar));

    Configuration packagedRuntimeElements = configureAttributes(configurations.consumable(PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME)
            .setDescription("Runtime elements for the packaged artifact.")
            .extendsFrom(implementation, runtimeOnly), details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
    packagedRuntimeElements.outgoing(o -> o.artifact(shadowJar));

    Configuration packagedRuntimeClasspath = configureAttributes(configurations.resolvable(PACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME)
            .setDescription("Runtime classpath of the packaged artifact.")
            .extendsFrom(implementation, runtimeOnly), details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));

    contentsRuntimeClasspath.getIncoming().beforeResolve(config -> {
      unpackagedRuntimeClasspath.getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
        ModuleVersionIdentifier identifier = resolvedArtifact.getModuleVersion().getId();
        contentsRuntimeClasspath.exclude(mapOf(String.class, String.class, "group", identifier.getGroup(), "module", identifier.getName()));
      });
    });

    shadowJar.configure(shadow -> {
      OsgiManifestJarExtension osgiExtension = new OsgiManifestJarExtension(shadow);
      osgiExtension.getClasspath().from(packagedRuntimeClasspath);
      osgiExtension.getSources().from(sources);

      osgiExtension.instructions.put(Constants.BUNDLE_VERSION, new MavenVersion(project.getVersion().toString()).getOSGiVersion().toString());
    });

    project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(packagedApiElements, variantDetails -> variantDetails.mapToMavenScope("compile"));
      java.addVariantsFromConfiguration(packagedRuntimeElements, variantDetails -> variantDetails.mapToMavenScope("runtime"));
    });

    project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
      task.dependsOn(shadowJar);
    });

    provided.getDependencies().configureEach(dependency -> {
      implementation.getDependencyConstraints().add(DefaultDependencyConstraint.strictly(dependency.getGroup(), dependency.getName(), dependency.getVersion()));
    });
  }

  private static String camelPrefix(String variant, String thing) {
    if (variant == null) {
      return thing;
    } else {
      return variant + capitalize(thing);
    }
  }

  public static class OsgiManifestJarExtension {

    private final ShadowJar jarTask;
    private final Property<Boolean> enabled;
    private final MapProperty<String, String> instructions;
    private final ConfigurableFileCollection classpath;
    private final ConfigurableFileCollection sources;

    public OsgiManifestJarExtension(ShadowJar jarTask) {
      this.jarTask = jarTask;
      this.enabled = jarTask.getProject().getObjects().property(Boolean.class).convention(true);
      this.instructions = jarTask.getProject().getObjects().mapProperty(String.class, String.class);
      this.classpath = jarTask.getProject().getObjects().fileCollection();
      this.sources = jarTask.getProject().getObjects().fileCollection();

      jarTask.getInputs().files(classpath).withNormalizer(ClasspathNormalizer.class).withPropertyName("osgi.classpath");
      jarTask.getInputs().files(sources).withPropertyName("osgi.sources");
      jarTask.getInputs().property("osgi.instructions", (Callable<Map<String, String>>) instructions::get);

      jarTask.getExtensions().add(OsgiManifestJarExtension.class, "osgi", this);
      jarTask.doLast("buildManifest", new BuildAction());
    }

    public Property<Boolean> getEnabled() {
      return enabled;
    }

    @Input
    @Classpath
    public ConfigurableFileCollection getClasspath() {
      return classpath;
    }

    @InputFiles
    public ConfigurableFileCollection getSources() {
      return sources;
    }

    @Input
    public MapProperty<String, String> getInstructions() {
      return instructions;
    }

    private class BuildAction implements Action<Task> {
      @Override
      public void execute(Task t) {
        if (enabled.get()) {
          try (Builder builder = new Builder()) {
            File archiveFile = jarTask.getArchiveFile().get().getAsFile();

            jarTask.getProject().sync(sync -> sync.from(archiveFile).into(jarTask.getTemporaryDir()));
            File archiveCopyFile = new File(jarTask.getTemporaryDir(), archiveFile.getName());

            try (aQute.bnd.osgi.Jar bundleJar = new aQute.bnd.osgi.Jar(archiveCopyFile)) {
              builder.setJar(bundleJar);
              builder.setClasspath(getClasspath().getFiles());
              builder.setSourcepath(getSources().getFiles().toArray(new File[0]));
              builder.addProperties(mergeInstructions(jarTask.getConfigurations(), getInstructions().get()));

              try (aQute.bnd.osgi.Jar builtJar = builder.build()) {
                builtJar.write(archiveFile);
              }

              if (!builder.isOk()) {
                jarTask.getProject().delete(archiveFile);
                builder.getErrors().forEach((String msg) -> {
                  Report.Location location = builder.getLocation(msg);
                  if ((location != null) && (location.file != null)) {
                    jarTask.getLogger().error("{}:{}: error: {}", location.file, location.line, msg);
                  } else {
                    jarTask.getLogger().error("error  : {}", msg);
                  }
                });
                throw new GradleException("Bundle " + archiveFile.getName() + " has errors");
              }
            }
          } catch (Exception e) {
            throw new GradleException("Error building bundle", e);
          }
        }
      }

      private Map<String, String> mergeInstructions(List<FileCollection> configurations, Map<String, String> userInstructions) throws Exception {
        Map<String, String> mergedInstructions = new HashMap<>(userInstructions);
        String userExportPackage = mergedInstructions.remove(Constants.EXPORT_PACKAGE);

        /*
         * Step 1: apply the local instructions (should just be negations)
         */
        List<String> exports = new ArrayList<>();
        exports.add(userExportPackage);

        /*
         * Step 2: derive instructions from any input JAR that has an OSGi manifest.
         */
        for (FileCollection files : configurations) {
          Configuration config = (Configuration) files;
          for (ResolvedArtifact artifact : config.getResolvedConfiguration().getResolvedArtifacts()) {
            try (aQute.bnd.osgi.Jar bndJar = new aQute.bnd.osgi.Jar(artifact.getFile())) {
              String exportHeader = bndJar.getManifest().getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
              if (exportHeader == null) {
                ShadowStats shadowStats = new ShadowStats();
                Function<String, String> packageRelocator = jarTask.getRelocators().stream().<Function<String, String>>map(relocator -> path -> {
                  if (relocator.canRelocateClass(path + ".")) {
                    String relocated = relocator.relocateClass(new RelocateClassContext(path + ".", shadowStats));
                    return relocated.substring(0, relocated.length() - 1);
                  } else {
                    return path;
                  }
                }).reduce((a, b) -> b.andThen(a)).orElse(identity());

                exports.addAll(bndJar.getPackages().stream().filter(p -> !(p.startsWith("META-INF") || p.startsWith("OSGI-INF") || p.isEmpty()))
                        .map(packageRelocator).map(p -> {
                          ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
                          if (componentIdentifier instanceof ModuleComponentIdentifier) {
                            return p + ";version=\"" + ((ModuleComponentIdentifier) componentIdentifier).getVersion() + "\"";
                          } else if (componentIdentifier instanceof ProjectComponentIdentifier) {
                            return p;
                          } else {
                            throw new IllegalArgumentException("Unhandled component identifier: " + componentIdentifier);
                          }
                        }).map(p -> "[0-9]?" + p).collect(Collectors.toList()));
              } else {
                // split the export header in to its separate instructions
                Matcher matcher = OSGI_EXPORT_PATTERN.matcher(exportHeader);
                while (matcher.find()) {
                  // strip the uses information and let BND calculate it for us again.
                  String export = matcher.group(1).replaceAll(";uses:?=\"[^\"]+\"", "");
                  // prefix the instructions with a no-op regex wildcard to prevent BND treating the pattern as a literal
                  // and overriding any earlier negations
                  exports.add("[0-9]?" + export);
                }
              }
            }
          }
        }

        mergedInstructions.put(Constants.EXPORT_PACKAGE, exports.stream().distinct().collect(joining(", ")));

        return mergedInstructions;
      }
    }
  }

  public class PackageProjectExtension {

    private final Project project;

    public PackageProjectExtension(Project project) {
      this.project = project;
    }

    private ConfigurationContainerInternal configurations() {
      return (ConfigurationContainerInternal) project.getConfigurations();
    }

    public void withOptionalFeature(String feature) {
      withOptionalFeature(feature, ConfigurationVariantDetails::mapToOptional);
    }

    public void withOptionalFeature(String feature, Action<ConfigurationVariantDetails> action) {
      Configuration api = createFeatureBucket(feature, API_CONFIGURATION_NAME)
              .setDescription("API dependencies for the packaged artifact '" + feature + "' feature.");
      Configuration implementation = createFeatureBucket(feature, IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(api)
              .setDescription("Implementation dependencies for the packaged artifact '" + feature + "' feature.");
      Configuration compileOnlyApi = createFeatureBucket(feature, COMPILE_ONLY_API_CONFIGURATION_NAME)
              .setDescription("Compile-only dependencies for the packaged artifact '" + feature + "' feature.");
      Configuration runtimeOnly = createFeatureBucket(feature, RUNTIME_ONLY_CONFIGURATION_NAME)
              .setDescription("Runtime-only dependencies for the packaged artifact '" + feature + "' feature.");

      Set<String> groupIds = project.getExtensions().getByType(PublishingExtension.class).getPublications().withType(MavenPublication.class).stream().map(MavenPublication::getGroupId).collect(toSet());
      Set<String> artifactIds = project.getExtensions().getByType(PublishingExtension.class).getPublications().withType(MavenPublication.class).stream().map(MavenPublication::getArtifactId).collect(toSet());

      if (groupIds.size() != 1) {
        throw new IllegalArgumentException("Publication groupId is unclear: " + groupIds);
      }
      String group = groupIds.iterator().next();
      if (artifactIds.size() != 1) {
        throw new IllegalArgumentException("Publication artifactId is unclear: " + artifactIds);
      }
      String featureCapability = group + ":" + artifactIds.iterator().next() + "-" + feature + ":" + project.getVersion();

      JavaVersions javaVersions = project.getExtensions().getByType(JavaVersions.class);

      Configuration packagedApiElements = configureAttributes(configurations().consumable(camelPrefix(feature, PACKAGED_API_ELEMENTS_CONFIGURATION_NAME))
              .setDescription("API elements for the packaged artifact '" + feature + "' feature.")
              .extendsFrom(api, compileOnlyApi), details -> details.apiUsage().library().asJar().withEmbeddedDependencies())
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
      packagedApiElements.outgoing(o -> {
        o.capability(featureCapability);
        o.artifact(project.getTasks().named(JAR_TASK_NAME));
      });

      Configuration packagedRuntimeElements = configureAttributes(configurations().consumable(camelPrefix(feature, PACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME))
              .setDescription("Runtime elements for the packaged artifact '" + feature + "' feature variant.")
              .extendsFrom(implementation, runtimeOnly), details -> details.withEmbeddedDependencies().runtimeUsage().library().asJar())
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
      packagedRuntimeElements.outgoing(outgoing -> {
        outgoing.capability(featureCapability);
        outgoing.artifact(project.getTasks().named(JAR_TASK_NAME));
      });

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(packagedApiElements, variantDetails -> {
          variantDetails.mapToMavenScope("compile");
          action.execute(variantDetails);
        });
        java.addVariantsFromConfiguration(packagedRuntimeElements, variantDetails -> {
          variantDetails.mapToMavenScope("runtime");
          action.execute(variantDetails);
        });
      });

      Configuration unpackagedApiElements = configureAttributes(configurations().consumable(camelPrefix(feature, UNPACKAGED_API_ELEMENTS_CONFIGURATION_NAME))
              .setDescription("API elements for the unpackaged '" + feature + "' feature contents.")
              .extendsFrom(api, compileOnlyApi, configurations().getByName(CONTENTS_API_CONFIGURATION_NAME)), details -> details.withExternalDependencies())
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
      unpackagedApiElements.outgoing(out -> out.capability(featureCapability));

      Configuration unpackagedRuntimeElements = configureAttributes(configurations().consumable(camelPrefix(feature, UNPACKAGED_RUNTIME_ELEMENTS_CONFIGURATION_NAME))
              .setDescription("Runtime elements for the unpackaged '" + feature + "' feature contents.")
              .extendsFrom(implementation, runtimeOnly, configurations().getByName(CONTENTS_CONFIGURATION_NAME)), details -> details.withExternalDependencies())
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt))
              .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, UNPACKAGED_JAVA_RUNTIME)));
      unpackagedRuntimeElements.outgoing(o -> o.capability(featureCapability));
    }

    public void withPackagedSourcesJar() {
      TaskProvider<Jar> sourcesJar = project.getTasks().register("sourcesJar", Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the packaged sources.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(project.getTasks().named(SOURCES_TASK_NAME));
        jar.from(project.getTasks().named(JAR_TASK_NAME), spec -> spec.include("META-INF/**", "LICENSE", "NOTICE"));
        jar.getArchiveClassifier().set("sources");
      });

      Configuration sourcesElements = configureAttributes(configurations().consumable(SOURCES_ELEMENTS_CONFIGURATION_NAME)
              .setDescription("Sources elements for the packaged artifact."), details -> details
              .runtimeUsage().withExternalDependencies().documentation(SOURCES));
      sourcesElements.outgoing(o -> o.artifact(sourcesJar));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(sourcesElements, variantDetails -> {});
      });

      project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(sourcesJar);
      });
    }

    public void withPackagedJavadocJar(Closure<Void> closure) {
      withPackagedJavadocJar(new ClosureBackedAction<>(closure));
    }

    public void withPackagedJavadocJar(Action<PackageJavadoc> action) {

      Configuration javadocClasspath = configurations().bucket("javadocClasspath")
              .setDescription("Additional javadoc generation dependencies.");
      action.execute(new PackageJavadoc(javadocClasspath, project.getDependencies()));
      Configuration additionalJavadocClasspath = configurations().resolvable("additionalJavadocClasspath")
              .setDescription("Additional classpath for javadoc generation.").extendsFrom(javadocClasspath);
      jvmPluginServices.configureAsRuntimeClasspath(additionalJavadocClasspath);

      TaskProvider<Javadoc> javadoc = project.getTasks().register(JAVADOC_TASK_NAME, Javadoc.class, task -> {
        task.setDescription("Generates Javadoc API documentation for the packaged source code.");
        task.setGroup(JavaBasePlugin.DOCUMENTATION_GROUP);
        task.setTitle(project.getName() + " " + project.getVersion() + " API");
        task.source(project.getTasks().named(SOURCES_TASK_NAME));
        task.include("**/*.java");
        task.setClasspath(project.getConfigurations().getByName(CONTENTS_RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                .plus(project.getConfigurations().getByName(UNPACKAGED_RUNTIME_CLASSPATH_CONFIGURATION_NAME))
                .plus(additionalJavadocClasspath));
        task.setDestinationDir(new File(project.getBuildDir(), JAVADOC_TASK_NAME));
      });
      TaskProvider<Jar> javadocJar = project.getTasks().register("javadocJar", Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the packaged javadoc.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadoc);
        jar.getArchiveClassifier().set("javadoc");
      });

      Configuration javadocElements = configureAttributes(configurations().consumable(JAVADOC_ELEMENTS_CONFIGURATION_NAME)
              .setDescription("Javadoc elements for the packaged artifact."), details -> details
              .runtimeUsage().withExternalDependencies().documentation(JAVADOC));
      javadocElements.outgoing(o -> o.artifact(javadocJar));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(javadocElements, variantDetails -> {});
      });

      project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(javadocJar);
      });
    }

    public class PackageJavadoc {

      private final Configuration configuration;
      private final DependencyHandler dependencyHandler;

      private PackageJavadoc(Configuration configuration, DependencyHandler dependencyHandler) {
        this.configuration = configuration;
        this.dependencyHandler = dependencyHandler;
      }

      public void classpath(Object object) {
        classpath(object, null);
      }

      public void classpath(Object object, Action<? super Dependency> action) {
        Dependency dependency = dependencyHandler.create(object);
        if (action != null) {
          action.execute(dependency);
        }
        configuration.getDependencies().add(dependency);
        configuration.getDependencies().add(dependency);
      }
    }

    public void withJavadocJarFrom(Object dependency) {

      Configuration javadoc = configurations().bucket("inheritedJavadoc")
              .setDescription("Dependencies for the inherited javadoc.");
      javadoc.getDependencies().add(project.getDependencies().create(dependency));

      Configuration javadocJars = configureAttributes(configurations().resolvable("inheritedJavadocJars")
              .setDescription("Inherited javadoc files for merging.")
              .extendsFrom(javadoc), details -> details.documentation(JAVADOC).asJar());

      TaskProvider<Jar> javadocJar = project.getTasks().register("javadocJar", Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the inherited javadoc.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadocJars.getElements().map(locations -> locations.stream().map(project::zipTree).toArray())).exclude("LICENSE");
        jar.getArchiveClassifier().set("javadoc");
      });

      Configuration javadocElements = configureAttributes(configurations().consumable(JAVADOC_ELEMENTS_CONFIGURATION_NAME),
              details -> details.runtimeUsage().withExternalDependencies().documentation(JAVADOC));
      javadocElements.outgoing(o -> o.artifact(javadocJar));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(javadocElements, variantDetails -> {});
      });

      project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(javadocJar);
      });
    }

    private Configuration createFeatureBucket(String feature, String bucket) {
      return configurations().bucket(camelPrefix(feature, bucket)).extendsFrom(configurations().getByName(bucket));
    }
  }

  private <T extends HasConfigurableAttributes<T>> T configureAttributes(T attributed, Action<? super JvmEcosystemAttributesDetails> details) {
    jvmPluginServices.configureAttributes(attributed, details);
    return attributed;
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