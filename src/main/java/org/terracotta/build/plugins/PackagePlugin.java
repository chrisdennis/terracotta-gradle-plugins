package org.terracotta.build.plugins;

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.internal.artifacts.JavaEcosystemSupport;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.internal.service.ServiceRegistry;
import org.terracotta.build.plugins.packaging.OsgiManifestJarExtension;
import org.terracotta.build.plugins.packaging.PackageInternal;
import org.terracotta.build.plugins.packaging.PackagingExtension;
import org.terracotta.build.plugins.packaging.PackagingExtensionInternal;

import static org.gradle.api.internal.tasks.JvmConstants.JAVA_COMPONENT_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.UNPACKAGED_JAVA_RUNTIME;

/**
 * EhDistribute
 */
public abstract class PackagePlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BasePlugin.class);

    ServiceRegistry projectServices = ((ProjectInternal) project).getServices();
    SoftwareComponentFactory softwareComponentFactory = projectServices.get(SoftwareComponentFactory.class);
    AdhocComponentWithVariants javaComponent = softwareComponentFactory.adhoc(JAVA_COMPONENT_NAME);
    project.getComponents().add(javaComponent);

    project.getTasks().withType(ShadowJar.class).configureEach(shadow -> shadow.getExtensions().create("osgi", OsgiManifestJarExtension.class, shadow));

    PackagingExtensionInternal packaging = (PackagingExtensionInternal) project.getExtensions().create(PackagingExtension.class, "packaging", PackagingExtensionInternal.class);
    packaging.getDefaultPackage().create();
    packaging.getVariants().all(PackageInternal::create);

    project.getPlugins().withType(MavenPublishPlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(PublishingExtension.class, publishing -> {
        publishing.getPublications().register("mavenJava", MavenPublication.class, mavenPublication -> {
          mavenPublication.from(javaComponent);
        });
      });
    });
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