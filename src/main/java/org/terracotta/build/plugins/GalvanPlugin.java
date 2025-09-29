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
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.provider.Provider;
import org.gradle.testing.base.TestingExtension;

import static java.util.Arrays.asList;

/**
 * Galvsn based testing plugin.
 * <p>
 * This plugin ensures the test task of the associated project is able to run Galvan tests. That consists of:
 * <ul>
 *   <li>Adding the galvan-support-ee test dependency</li>
 *   <li>Ensuring test test-kit has been built and linking to it properly</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class GalvanPlugin extends AbstractKitTestingPlugin {

  public static final String FRAMEWORK_CONFIGURATION_NAME = "galvan";

  @Override
  protected String name() {
    return "galvan";
  }

  @Override
  public void apply(Project project) {
    super.apply(project);

    Provider<Directory> galvanDir = project.getLayout().getBuildDirectory().dir("galvan");
    getCustomKitDirectory().set(galvanDir.map(d -> d.dir("custom-tc-db-kit")));

    ConfigurationContainer configurations = project.getConfigurations();

    NamedDomainObjectProvider<DependencyScopeConfiguration> galvan = configurations.dependencyScope(FRAMEWORK_CONFIGURATION_NAME,
        c -> c.defaultDependencies(defaultDeps -> defaultDeps.add(project.getDependencyFactory().create("org.terracotta", "terracotta-dynamic-config-testing-galvan", "[5,)"))));

    project.getExtensions().configure(TestingExtension.class, testing -> testing.getSuites().withType(JvmTestSuite.class).configureEach(testSuite -> {
      configurations.named(testSuite.getSources().getImplementationConfigurationName(), config -> config.extendsFrom(galvan.get()));

      testSuite.getTargets().configureEach(target -> target.getTestTask().configure(task -> {
        task.getJvmArgumentProviders().add(() -> asList(
            "-DkitInstallationPath=" + getKitDirectory().get().getAsFile().getAbsolutePath(),
            "-DkitTestDirectory=" + galvanDir.get().getAsFile().getAbsolutePath()
        ));
        // Use -Pgalvan.noclean to prevent Galvan from discarding test directories
        if (project.hasProperty("galvan.noclean")) {
          task.systemProperty("galvan.noclean", project.property("galvan.noclean"));
        }
      }));
    }));
  }
}
