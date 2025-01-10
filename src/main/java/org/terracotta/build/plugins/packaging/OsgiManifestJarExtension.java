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

import aQute.bnd.osgi.Builder;
import aQute.bnd.osgi.Constants;
import aQute.service.reporter.Report;
import com.github.jengelman.gradle.plugins.shadow.ShadowStats;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocateClassContext;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

public class OsgiManifestJarExtension {

  private static final Pattern OSGI_EXPORT_PATTERN = Pattern.compile("([^;,]+((?:;[^,:=]+:?=\"[^\"]+\")*))(?:,|$)");

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
