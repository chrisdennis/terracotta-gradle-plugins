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

import aQute.bnd.osgi.Constants;
import aQute.bnd.version.MavenVersion;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.terracotta.build.plugins.packaging.OsgiManifestJarExtension;
import org.terracotta.build.plugins.packaging.PackageInternal;
import org.terracotta.build.plugins.packaging.PackagingExtension;
import org.terracotta.build.plugins.packaging.PackagingExtensionInternal;

public class OsgiPackagePlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(PackagePlugin.class);

    PackagingExtensionInternal packaging = (PackagingExtensionInternal) project.getExtensions().getByType(PackagingExtension.class);

    Action<PackageInternal> osgiSetup = pkg -> project.getTasks().named(pkg.getJarTaskName(), ShadowJar.class).configure(shadow -> {
      OsgiManifestJarExtension osgi = shadow.getExtensions().create("osgi", OsgiManifestJarExtension.class, shadow);
      osgi.getClasspath().from(project.getConfigurations().named(pkg.getPackagedRuntimeClasspathConfigurationName()));
      osgi.getSources().from(pkg.getSourcesTaskName());
      osgi.instruction(Constants.BUNDLE_VERSION, new MavenVersion(project.getVersion().toString()).getOSGiVersion().toString());
    });

    osgiSetup.execute(packaging.getDefaultPackage());
    packaging.getVariants().all(osgiSetup);
  }
}
