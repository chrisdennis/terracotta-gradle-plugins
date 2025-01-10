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
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.file.*;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.testing.base.TestingExtension;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

@SuppressWarnings("UnstableApiUsage")
public abstract class AbstractKitTestingPlugin implements Plugin<Project> {

  private final DirectoryProperty kitDirectory = getObjectFactory().directoryProperty();

  protected abstract String name();

  protected String prefixed(String string) {
    return name() + string;
  }

  @Inject
  protected abstract ObjectFactory getObjectFactory();

  @Inject
  protected abstract JvmPluginServices getJvmPluginServices();

  protected abstract DirectoryProperty getCustomKitDirectory();

  protected Provider<Directory> getKitDirectory() {
    return kitDirectory;
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(JvmEcosystemPlugin.class);
    project.getPlugins().apply(JvmTestSuitePlugin.class);

    ConfigurationContainer configurations = project.getConfigurations();

    NamedDomainObjectProvider<DependencyScopeConfiguration> kit = configurations.dependencyScope(prefixed("Kit"));
    NamedDomainObjectProvider<ResolvableConfiguration> kitPath = configurations.resolvable(prefixed("KitPath"), c -> c.extendsFrom(kit.get()));
    NamedDomainObjectProvider<DependencyScopeConfiguration> serverPlugins = configurations.dependencyScope(prefixed("ServerPlugins"));
    NamedDomainObjectProvider<ResolvableConfiguration> serverPluginsClasspath = configurations.resolvable(prefixed("ServerPluginsClasspath"), c -> {
      c.extendsFrom(serverPlugins.get());
      getJvmPluginServices().configureAsRuntimeClasspath(c);
    });

    Provider<FileTree> kitTree = kitPath.flatMap(c -> c.getElements().map(e -> {
      if (e.size() == 1) {
        File singleFile = e.iterator().next().getAsFile();
        if (singleFile.isDirectory()) {
          return project.getObjects().fileTree().from(singleFile);
        } else if (singleFile.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
          return project.zipTree(singleFile);
        } else {
          return project.tarTree(singleFile);
        }
      } else {
        throw new IllegalStateException("Too many kit dependencies");
      }
    }));

    Provider<Sync> kitPreparation = project.getTasks().register(prefixed("KitPreparation"), Sync.class, task -> {
      task.onlyIf(t -> !serverPluginsClasspath.get().isEmpty() || kitPath.get().getSingleFile().isFile());

      task.from(kitTree);
      task.into(getCustomKitDirectory());

      Predicate<String> pruning = Stream.of("server/plugins/api", "server/lib")
          .map(dir -> task.getDestinationDir().toPath().resolve(dir))
          .map(dir -> (Predicate<String>) (String file) -> Files.exists(dir.resolve(file)))
          .reduce(Predicate::or).orElse(f -> false);

      task.from(serverPluginsClasspath, spec -> {
        spec.into("server/plugins/lib");
        spec.eachFile(fcd -> {
          if (pruning.test(fcd.getName())) {
            fcd.exclude();
          }
        });
        spec.filesMatching("**/*.jar", fcd -> {
          fcd.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        });
      });
    });

    kitDirectory.set(kitPreparation.flatMap(prep -> {
      if (prep.isEnabled() && prep.getOnlyIf().isSatisfiedBy(prep)) {
        return getCustomKitDirectory();
      } else {
        return project.getLayout().dir(kitPath.map(FileCollection::getSingleFile));
      }
    }));

    project.getExtensions().configure(TestingExtension.class, testing -> testing.getSuites().withType(JvmTestSuite.class)
        .configureEach(testSuite -> testSuite.getTargets().configureEach(target -> target.getTestTask()
            .configure(task -> task.dependsOn(kitPreparation)))));
  }
}
