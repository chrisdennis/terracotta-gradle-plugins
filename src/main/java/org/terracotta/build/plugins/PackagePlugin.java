package org.terracotta.build.plugins;

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.internal.artifacts.JavaEcosystemSupport;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.terracotta.build.plugins.packaging.OsgiManifestJarExtension;
import org.terracotta.build.plugins.packaging.PackageInternal;
import org.terracotta.build.plugins.packaging.PackagingExtension;
import org.terracotta.build.plugins.packaging.PackagingExtensionInternal;

import javax.inject.Inject;

import static org.gradle.api.artifacts.Dependency.DEFAULT_CONFIGURATION;
import static org.gradle.api.internal.tasks.JvmConstants.RUNTIME_ELEMENTS_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.CONTENTS_API_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.CONTENTS_CONFIGURATION_NAME;
import static org.terracotta.build.plugins.packaging.PackageInternal.camelPrefix;

/**
 * A plugin which assembles partial uber-jars.
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class PackagePlugin implements Plugin<Project> {

  public static final String PACKAGE_COMPONENT_NAME = "package";
  public static final String EXPLODED_JAVA_RUNTIME = "exploded-java-runtime";
  public static final String COMMON_PREFIX = "common";

  @Inject
  protected abstract SoftwareComponentFactory getSoftwareComponentFactory();

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BasePlugin.class);

    project.getComponents().registerFactory(AdhocComponentWithVariants.class, getSoftwareComponentFactory()::adhoc);
    NamedDomainObjectProvider<AdhocComponentWithVariants> component = project.getComponents().register(PACKAGE_COMPONENT_NAME, AdhocComponentWithVariants.class);

    project.getTasks().withType(ShadowJar.class).configureEach(shadow -> shadow.getExtensions().create("osgi", OsgiManifestJarExtension.class, shadow));


    ConfigurationContainer configurations = project.getConfigurations();
    /*
     * Create the set of common dependency scopes that all the package specific scopes extend.
     */
    NamedDomainObjectProvider<DependencyScopeConfiguration> commonContentsApi = configurations.dependencyScope(camelPrefix(COMMON_PREFIX, CONTENTS_API_CONFIGURATION_NAME),
        c -> c.setDescription("API dependencies for all packages contents."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, CONTENTS_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonContentsApi.get()).setDescription("Implementation dependencies for all packages contents."));

    NamedDomainObjectProvider<DependencyScopeConfiguration> commonApi = configurations.dependencyScope(camelPrefix(COMMON_PREFIX, API_CONFIGURATION_NAME),
        c -> c.setDescription("API dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, IMPLEMENTATION_CONFIGURATION_NAME),
        c -> c.extendsFrom(commonApi.get()).setDescription("Implementation dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, COMPILE_ONLY_API_CONFIGURATION_NAME),
        c -> c.setDescription("Compile-only API dependencies for all packaged artifacts."));
    configurations.dependencyScope(camelPrefix(COMMON_PREFIX, RUNTIME_ONLY_CONFIGURATION_NAME),
        c -> c.setDescription("Runtime-only dependencies for all packaged artifacts."));

    PackagingExtensionInternal packaging = (PackagingExtensionInternal) project.getExtensions().create(PackagingExtension.class, "packaging", PackagingExtensionInternal.class);
    packaging.getDefaultPackage().create();
    packaging.getVariants().all(PackageInternal::create);

    configurations.named(DEFAULT_CONFIGURATION).configure(c -> c.extendsFrom(configurations.getByName(RUNTIME_ELEMENTS_CONFIGURATION_NAME)));

    project.getPlugins().withType(MavenPublishPlugin.class).configureEach(plugin -> {
      project.getExtensions().configure(PublishingExtension.class, publishing -> {
        publishing.getPublications().register("mavenPackage", MavenPublication.class, mavenPublication -> {
          mavenPublication.from(component.get());
        });
      });
    });
  }

  public static void augmentAttributeSchema(AttributesSchema schema) {
    schema.attribute(Usage.USAGE_ATTRIBUTE, strategy -> strategy.getCompatibilityRules().add(ExplodedJavaRuntimeCompatibility.class));
  }

  @SuppressWarnings("deprecation")
  public static class ExplodedJavaRuntimeCompatibility implements AttributeCompatibilityRule<Usage> {

    @Override
    public void execute(CompatibilityCheckDetails<Usage> details) {
      Usage consumerValue = details.getConsumerValue();
      Usage producerValue = details.getProducerValue();

      if (consumerValue == null) {
        details.compatible();
        return;
      }

      if (producerValue != null && EXPLODED_JAVA_RUNTIME.equals(consumerValue.getName())) {
        switch (producerValue.getName()) {
          case Usage.JAVA_RUNTIME:
            case JavaEcosystemSupport.DEPRECATED_JAVA_RUNTIME_JARS:
            details.compatible();
            break;
        }
      }
    }
  }
}