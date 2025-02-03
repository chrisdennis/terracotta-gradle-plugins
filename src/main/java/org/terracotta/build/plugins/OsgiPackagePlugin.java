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
