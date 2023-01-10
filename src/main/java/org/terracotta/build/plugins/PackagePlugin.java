package org.terracotta.build.plugins;

import aQute.bnd.osgi.Builder;
import aQute.bnd.osgi.Constants;
import aQute.bnd.version.MavenVersion;
import aQute.service.reporter.Report;
import com.github.jengelman.gradle.plugins.shadow.ShadowStats;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocateClassContext;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
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
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.jvm.internal.JvmEcosystemAttributesDetails;
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
import org.terracotta.build.Utils;
import org.terracotta.build.plugins.JavaVersionPlugin.JavaVersions;

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
import static org.terracotta.build.PluginUtils.bucket;
import static org.terracotta.build.PluginUtils.capitalize;
import static org.terracotta.build.PluginUtils.createBucket;
import static org.terracotta.build.Utils.mapOf;

/**
 * EhDistribute
 */
public class PackagePlugin implements Plugin<Project> {

  public static final String UNSHADED_JAVA_RUNTIME = "unshaded-java-runtime";

  private static final String CONTENTS_CONFIGURATION_NAME = "contents";
  private static final String CONTENTS_API_CONFIGURATION_NAME = "contentsApi";
  private static final String CONTENTS_RUNTIME_ELEMENTS_CONFIGURATION_NAME = "contentsRuntimeElements";
  private static final String CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME = "contentsSourcesElements";

  private static final Pattern OSGI_EXPORT_PATTERN = Pattern.compile("((?:[^;,]+)((?:;[^,:=]+:?=\"[^\"]+\")*))(?:,|$)");
  private static final String SOURCES_TASK_NAME = "sources";

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
    ServiceRegistry projectServices = ((ProjectInternal) project).getServices();
    JvmPluginServices jvmPluginServices = projectServices.get(JvmPluginServices.class);

    JavaVersions javaVersions = project.getExtensions().getByType(JavaVersions.class);

    Configuration contentsApi = createBucket(project, CONTENTS_API_CONFIGURATION_NAME);
    Configuration contents = createBucket(project, CONTENTS_CONFIGURATION_NAME).extendsFrom(contentsApi);

    Configuration contentsRuntimeElements = jvmPluginServices.createResolvableConfiguration(CONTENTS_RUNTIME_ELEMENTS_CONFIGURATION_NAME, builder -> builder
                    .extendsFrom(contents).requiresJavaLibrariesRuntime())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));

    /*
     * The variant metadata rules are not complex enough, nor applied uniformly enough to give us the "transient sources"
     * configuration that we need. Instead, we populate the contentSourcesElements configuration using the resolved
     * artifacts of the shadow contents configuration.
     */
    Configuration contentsSourcesElements = jvmPluginServices.createResolvableConfiguration(CONTENTS_SOURCES_ELEMENTS_CONFIGURATION_NAME, builder ->
            builder.requiresAttributes(refiner -> refiner.documentation(SOURCES))).withDependencies(config -> {
      contentsRuntimeElements.getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
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
      });
    }).resolutionStrategy(resolutionStrategy -> ((ResolutionStrategyInternal) resolutionStrategy).assumeFluidDependencies());

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
      sync.setDescription("Collects the sources contributing to this shaded artifact.");
      sync.setGroup(DOCUMENTATION_GROUP);
      sync.dependsOn(contentsSourcesElements);
      sync.from(sourcesTree, spec -> spec.exclude("META-INF/**", "LICENSE", "NOTICE"));
      sync.into(project.getLayout().getBuildDirectory().dir("sources"));
    });

    Configuration api = createBucket(project, API_CONFIGURATION_NAME);
    Configuration implementation = createBucket(project, IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(api);
    Configuration compileOnlyApi = createBucket(project, COMPILE_ONLY_API_CONFIGURATION_NAME);
    Configuration runtimeOnly = createBucket(project, RUNTIME_ONLY_CONFIGURATION_NAME);

    Configuration unshadedApiElements = jvmPluginServices.createOutgoingElements("unshadedApiElements", builder -> builder
                    .extendsFrom(api, compileOnlyApi, contentsApi).providesApi()
                    .providesAttributes(JvmEcosystemAttributesDetails::withExternalDependencies))
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
    jvmPluginServices.createOutgoingElements("unshadedRuntimeElements", builder -> builder
                    .extendsFrom(implementation, runtimeOnly, contents).providesAttributes(JvmEcosystemAttributesDetails::withExternalDependencies))
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt))
                    .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, UNSHADED_JAVA_RUNTIME)));
    Configuration unshadedRuntimeClasspath = jvmPluginServices.createResolvableConfiguration("unshadedRuntimeClasspath", builder -> builder
                    .extendsFrom(implementation, runtimeOnly).requiresAttributes(JvmEcosystemAttributesDetails::withExternalDependencies))
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt))
                    .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, UNSHADED_JAVA_RUNTIME)));

    TaskProvider<ShadowJar> shadowJar = project.getTasks().register(JAR_TASK_NAME, ShadowJar.class, shadow -> {
      shadow.setDescription("Assembles a jar archive containing the shaded classes.");
      shadow.setGroup(BasePlugin.BUILD_GROUP);

      shadow.setConfigurations(Collections.singletonList(contentsRuntimeElements));

      shadow.mergeServiceFiles();

      shadow.exclude("META-INF/MANIFEST.MF", "LICENSE", "NOTICE");

      // LICENSE is included in root gradle build
      shadow.from(new File(project.getRootDir(), "NOTICE"));
      shadow.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);

      unshadedApiElements.exclude(mapOf(String.class, String.class, "group", "org.terracotta", "module", "statistics"));
      shadow.relocate("org.terracotta.statistics.", "com.terracottatech.shadow.org.terracotta.statistics.");
      shadow.relocate("org.terracotta.context.", "com.terracottatech.shadow.org.terracotta.context.");

      unshadedApiElements.exclude(mapOf(String.class, String.class, "group", "com.terracottatech", "module", "offheap-restartable-store"));
      shadow.relocate("com.terracottatech.offheapstore.", "com.terracottatech.shadow.com.terracottatech.offheapstore.");

      unshadedApiElements.exclude(mapOf(String.class, String.class, "group", "org.terracotta", "module", "offheap-store"));
      shadow.relocate("org.terracotta.offheapstore.", "com.terracottatech.shadow.org.terracotta.offheapstore.");

      unshadedApiElements.exclude(mapOf(String.class, String.class, "group", "com.terracottatech", "module", "fast-restartable-store"));
      shadow.relocate("com.terracottatech.frs.", "com.terracottatech.shadow.com.terracottatech.frs.");
    });

    Configuration shadedApiElements = jvmPluginServices.createOutgoingElements("shadedApiElements", builder -> builder
                    .extendsFrom(api, compileOnlyApi).artifact(shadowJar).published().providesApi()
                    .providesAttributes(JvmEcosystemAttributesDetails::withEmbeddedDependencies))
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
    Configuration shadedRuntimeElements = jvmPluginServices.createOutgoingElements("shadedRuntimeElements", builder -> builder
                    .extendsFrom(implementation, runtimeOnly).artifact(shadowJar).published().providesRuntime()
                    .providesAttributes(JvmEcosystemAttributesDetails::withEmbeddedDependencies))
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
    Configuration shadedRuntimeClasspath = jvmPluginServices.createResolvableConfiguration("shadedRuntimeClasspath", builder -> builder
                    .extendsFrom(implementation, runtimeOnly).requiresAttributes(JvmEcosystemAttributesDetails::withEmbeddedDependencies).requiresJavaLibrariesRuntime())
            .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));

    contentsRuntimeElements.getIncoming().beforeResolve(config -> {
      unshadedRuntimeClasspath.getResolvedConfiguration().getResolvedArtifacts().forEach(resolvedArtifact -> {
        ModuleVersionIdentifier identifier = resolvedArtifact.getModuleVersion().getId();
        contentsRuntimeElements.exclude(mapOf(String.class, String.class, "group", identifier.getGroup(), "module", identifier.getName()));
      });
    });

    shadowJar.configure(shadow -> {
      OsgiManifestJarExtension osgiExtension = new OsgiManifestJarExtension(shadow);
      osgiExtension.getClasspath().from(shadedRuntimeClasspath);
      osgiExtension.getSources().from(sources);

      osgiExtension.instruction(Constants.BUNDLE_VERSION, new MavenVersion(project.getVersion().toString()).getOSGiVersion().toString());
    });

    project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
      java.addVariantsFromConfiguration(shadedApiElements, variantDetails -> variantDetails.mapToMavenScope("compile"));
      java.addVariantsFromConfiguration(shadedRuntimeElements, variantDetails -> variantDetails.mapToMavenScope("runtime"));
    });

    project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
      task.dependsOn(shadowJar);
    });
  }

  private static String camelPrefix(String variant, String thing) {
    if (variant == null) {
      return thing;
    } else {
      return variant + capitalize(thing);
    }
  }

  private static String kebabPrefix(String variant, String thing) {
    if (variant == null) {
      return thing;
    } else {
      return variant + "-" + thing;
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

      jarTask.getExtensions().add("osgi", this);
      jarTask.doLast("buildManifest", new BuildAction());
    }

    public void enabled(boolean value) {
      enabled.set(value);
    }

    public void instruction(String key, String value) {
      instructions.put(key, value);
    }

    public void instruction(String key, Provider<String> value) {
      instructions.put(key, value);
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
         * Step 2: derive instructions from any shade input JAR that has a OSGi manifest.
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

  public static class PackageProjectExtension {

    private final Project project;
    private final JvmPluginServices jvmPluginServices;

    public PackageProjectExtension(Project project) {
      this.project = project;
      ServiceRegistry projectServices = ((ProjectInternal) project).getServices();
      this.jvmPluginServices = projectServices.get(JvmPluginServices.class);
    }

    public void withOptionalFeature(String feature) {
      Configuration api = createFeatureBucket(project, feature, API_CONFIGURATION_NAME);
      Configuration implementation = createFeatureBucket(project, feature, IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(api);
      Configuration compileOnlyApi = createFeatureBucket(project, feature, COMPILE_ONLY_API_CONFIGURATION_NAME);
      Configuration runtimeOnly = createFeatureBucket(project, feature, RUNTIME_ONLY_CONFIGURATION_NAME);

      Set<String> groupIds = project.getExtensions().getByType(PublishingExtension.class).getPublications().withType(MavenPublication.class).stream().map(MavenPublication::getGroupId).collect(toSet());
      Set<String> artifactIds = project.getExtensions().getByType(PublishingExtension.class).getPublications().withType(MavenPublication.class).stream().map(MavenPublication::getArtifactId).collect(toSet());

      if (groupIds.size() != 1) {
        throw new IllegalArgumentException("Publication groupId is unclear: " + groupIds);
      }
      String group = groupIds.iterator().next();
      if (artifactIds.size() != 1) {
        throw new IllegalArgumentException("Publication artifactId is unclear: " + artifactIds);
      }
      String capability = artifactIds.iterator().next() + "-" + feature;

      JavaVersions javaVersions = project.getExtensions().getByType(JavaVersions.class);

      Configuration shadedApiElements = jvmPluginServices.createOutgoingElements(camelPrefix(feature, "shadedApiElements"), builder ->
                      builder.extendsFrom(api, compileOnlyApi).published().providesApi().capability(group, capability, project.getVersion().toString())
                              .providesAttributes(JvmEcosystemAttributesDetails::withEmbeddedDependencies).artifact(project.getTasks().named(JAR_TASK_NAME)))
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
      Configuration shadedRuntimeElements = jvmPluginServices.createOutgoingElements(camelPrefix(feature, "shadedRuntimeElements"), builder ->
                      builder.extendsFrom(implementation, runtimeOnly).published().providesRuntime().capability(group, capability, project.getVersion().toString())
                              .providesAttributes(JvmEcosystemAttributesDetails::withEmbeddedDependencies).artifact(project.getTasks().named(JAR_TASK_NAME)))
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(shadedApiElements, variantDetails -> {
          variantDetails.mapToMavenScope("compile");
          variantDetails.mapToOptional();
        });
        java.addVariantsFromConfiguration(shadedRuntimeElements, variantDetails -> {
          variantDetails.mapToMavenScope("runtime");
          variantDetails.mapToOptional();
        });
      });

      jvmPluginServices.createOutgoingElements(camelPrefix(feature, "unshadedApiElements"), builder ->
                      builder.extendsFrom(api, compileOnlyApi, bucket(project, CONTENTS_API_CONFIGURATION_NAME))
                              .providesApi().capability(group, capability, project.getVersion().toString())
                              .providesAttributes(JvmEcosystemAttributesDetails::withExternalDependencies))
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt)));
      jvmPluginServices.createOutgoingElements(camelPrefix(feature, "unshadedRuntimeElements"), builder ->
                      builder.extendsFrom(implementation, runtimeOnly, bucket(project, CONTENTS_CONFIGURATION_NAME))
                              .capability(group, capability, project.getVersion().toString())
                              .providesAttributes(JvmEcosystemAttributesDetails::withExternalDependencies))
              .attributes(attr -> attr.attributeProvider(TARGET_JVM_VERSION_ATTRIBUTE, javaVersions.getCompileVersion().map(JavaLanguageVersion::asInt))
                      .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, UNSHADED_JAVA_RUNTIME)));
    }

    public void withShadedSourcesJar() {
      TaskProvider<Jar> sourcesJar = project.getTasks().register("sourcesJar", Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the shaded sources.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(project.getTasks().named(SOURCES_TASK_NAME));
        jar.from(project.getTasks().named(JAR_TASK_NAME), spec -> spec.include("META-INF/**", "LICENSE", "NOTICE"));
        jar.getArchiveClassifier().set("sources");
      });

      Configuration sourcesElements = jvmPluginServices.createOutgoingElements(SOURCES_ELEMENTS_CONFIGURATION_NAME, builder ->
              builder.published().artifact(sourcesJar).providesAttributes(attributes -> attributes.documentation(SOURCES).asJar()));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(sourcesElements, variantDetails -> {});
      });

      project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(sourcesJar);
      });
    }

    public void withShadedJavadocJar(Closure<Void> closure) {
      withShadedJavadocJar(new ClosureBackedAction<>(closure));
    }

    public void withShadedJavadocJar(Action<PackageJavadoc> action) {
      Configuration javadocClasspath = createBucket(project, "javadocClasspath");
      action.execute(new PackageJavadoc(javadocClasspath, project.getDependencies()));
      Configuration additionalJavadocClasspath = jvmPluginServices.createResolvableConfiguration("additionalJavadocClasspath", builder -> builder.extendsFrom(javadocClasspath).requiresJavaLibrariesRuntime());

      TaskProvider<Javadoc> javadoc = project.getTasks().register(JAVADOC_TASK_NAME, Javadoc.class, task -> {
        task.setDescription("Generates Javadoc API documentation for the shade source code.");
        task.setGroup(JavaBasePlugin.DOCUMENTATION_GROUP);
        task.setTitle(project.getName() + " " + project.getVersion() + " API");
        task.source(project.getTasks().named(SOURCES_TASK_NAME));
        task.include("**/*.java");
        task.setClasspath(project.getConfigurations().getByName(CONTENTS_RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .plus(project.getConfigurations().getByName("unshadedRuntimeClasspath"))
                .plus(additionalJavadocClasspath));
        task.setDestinationDir(new File(project.getBuildDir(), JAVADOC_TASK_NAME));
      });
      TaskProvider<Jar> javadocJar = project.getTasks().register("javadocJar", Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the shaded javadoc.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadoc);
        jar.getArchiveClassifier().set("javadoc");
      });

      Configuration javadocElements = jvmPluginServices.createOutgoingElements(JAVADOC_ELEMENTS_CONFIGURATION_NAME, builder ->
              builder.published().artifact(javadocJar).providesAttributes(attributes -> attributes.documentation(JAVADOC).asJar()));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(javadocElements, variantDetails -> {});
      });

      project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(javadocJar);
      });
    }

    public static class PackageJavadoc {

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
      }
    }

    public void withJavadocJarFrom(Object dependency) {

      Configuration javadoc = createBucket(project, "inheritedJavadoc");
      javadoc.getDependencies().add(project.getDependencies().create(dependency));

      Configuration javadocJars = jvmPluginServices.createResolvableConfiguration("inheritedJavadocJars", builder -> builder.extendsFrom(javadoc)
              .requiresAttributes(attributes -> attributes.documentation(JAVADOC).asJar()));


      TaskProvider<Jar> javadocJar = project.getTasks().register("javadocJar", Jar.class, jar -> {
        jar.setDescription("Assembles a jar archive containing the inherited javadoc.");
        jar.setGroup(BasePlugin.BUILD_GROUP);
        jar.from(javadocJars.getElements().map(locations -> locations.stream().map(project::zipTree).toArray())).exclude("LICENSE");
        jar.getArchiveClassifier().set("javadoc");
      });

      Configuration javadocElements = jvmPluginServices.createOutgoingElements(JAVADOC_ELEMENTS_CONFIGURATION_NAME, builder ->
              builder.published().artifact(javadocJar).providesAttributes(attributes -> attributes.documentation(JAVADOC).asJar()));

      project.getComponents().named("java", AdhocComponentWithVariants.class, java -> {
        java.addVariantsFromConfiguration(javadocElements, variantDetails -> {});
      });

      project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> {
        task.dependsOn(javadocJar);
      });
    }
  }

  private static Configuration createFeatureBucket(Project project, String feature, String bucket) {
    return createBucket(project, camelPrefix(feature, bucket)).extendsFrom(bucket(project, bucket));
  }
}