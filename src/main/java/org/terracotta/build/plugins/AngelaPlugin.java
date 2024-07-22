/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.testing.base.TestingExtension;

import javax.inject.Inject;
import java.util.Collections;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.terracotta.build.plugins.CustomLocalHostPlugin.hostsTaskName;

/**
 * Angela based testing plugin.
 * <p>
 * This plugin ensures the test task of the associated project is able to run Angela tests. That consists of:
 * <ul>
 *   <li>Adding the necessary test dependencies: angela, and the kit-resolver</li>
 *   <li>Sourcing a suitable kit build, either the central test-kit, or copying and amending it if extra
 *   {@code pluginLibs} are configured.</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class AngelaPlugin extends AbstractKitTestingPlugin {

  public static final String FRAMEWORK_CONFIGURATION_NAME = "angela";

  @Inject
  protected abstract JavaForkOptionsFactory getJavaForkOptionsFactory();

  @Override
  protected String name() {
    return "angela";
  }

  @Override
  public void apply(Project project) {
    super.apply(project);

    Provider<Directory> angelaDir = project.getLayout().getBuildDirectory().dir("angela");
    getCustomKitDirectory().set(angelaDir.map(d -> d.dir("custom-tc-db-kit")));

    ConfigurationContainer configurations = project.getConfigurations();

    NamedDomainObjectProvider<DependencyScopeConfiguration> angela = configurations.dependencyScope(FRAMEWORK_CONFIGURATION_NAME,
        c -> c.defaultDependencies(defaultDeps -> defaultDeps.add(project.getDependencyFactory().create("org.terracotta", "angela", "[3,)"))));

    project.getExtensions().configure(TestingExtension.class, testing -> testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
      configurations.named(testSuite.getSources().getImplementationConfigurationName(), config -> config.extendsFrom(angela.get()));

      testSuite.getTargets().configureEach(target -> target.getTestTask().configure(task -> {
        task.systemProperty("IGNITE_UPDATE_NOTIFIER", "false");
        task.systemProperty("angela.rootDir", angelaDir.get().getAsFile().getAbsolutePath());
        task.systemProperty("angela.skipUninstall", "true");
        task.systemProperty("angela.tsa.fullLogging", "true");
        task.systemProperty("angela.igniteLogging", "false");
        task.systemProperty("angela.java.resolver", "user"); // disable toolchain, trust JAVA_HOME
        task.systemProperty("angela.distribution", requireNonNull(project.property("version")));
        task.getJvmArgumentProviders().add(() -> singleton("-Dangela.kitInstallationDir=" + getKitDirectory().get().getAsFile().getAbsolutePath()));

        JavaForkOptions angelaForkOptions = getJavaForkOptionsFactory().newDecoratedJavaForkOptions();
        task.getExtensions().add("angela", angelaForkOptions);
        angelaForkOptions.setMinHeapSize("64m");
        angelaForkOptions.setMaxHeapSize("512m");
        angelaForkOptions.systemProperty("IGNITE_UPDATE_NOTIFIER", false);

        task.getJvmArgumentProviders().add(() -> Collections.singleton("-Dangela.java.opts=" + angelaForkOptions.getAllJvmArgs().stream().collect(joining(" "))));

        project.getPlugins().withType(CustomLocalHostPlugin.class).configureEach(hostsPlugin -> {
          CustomHostPluginExtension hostsExtension = task.getExtensions().findByType(CustomHostPluginExtension.class);
          ListProperty<String> customHostList = hostsExtension.getCustomLocalHosts();
          task.getJvmArgumentProviders().add(() -> {
            if (!customHostList.get().isEmpty()) {
              String customHostNames = String.join(" ", customHostList.get());
              return singleton("-Dangela.additionalLocalHostnames=" + customHostNames);
            } else {
              return emptyList();
            }
          });
          TaskProvider<CustomLocalHostPlugin.WriteHostsFile> writeHostsFile = project.getTasks().named(hostsTaskName(task), CustomLocalHostPlugin.WriteHostsFile.class);
          angelaForkOptions.getJvmArgumentProviders().add(() -> {
            if (!customHostList.get().isEmpty()) {
              return singleton("-Djdk.net.hosts.file=" + writeHostsFile.get().getHostsFile().getAsFile().get().getAbsolutePath());
            } else {
              return emptyList();
            }
          });
        });
      }));
    }));
  }
}
