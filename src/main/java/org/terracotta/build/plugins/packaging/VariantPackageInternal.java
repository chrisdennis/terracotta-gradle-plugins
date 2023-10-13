package org.terracotta.build.plugins.packaging;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRole;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRoles;

import static java.text.MessageFormat.format;
import static org.gradle.api.internal.artifacts.configurations.ConfigurationRoles.BUCKET;
import static org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME;
import static org.terracotta.build.PluginUtils.capitalize;

public abstract class VariantPackageInternal extends PackageInternal implements VariantPackage, CustomCapabilitiesInternal {

  public static final String COMMON_PREFIX = "common";

  @Override
  public void create() {
    super.create();
    ensureAndLinkCommonBuckets();
  }

  private void ensureAndLinkCommonBuckets() {
    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) getProject().getConfigurations();

    Configuration commonContentsApi = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, CONTENTS_API_CONFIGURATION_NAME), BUCKET)
            .setDescription(description("API dependencies for all packages contents."));
    Configuration commonContents = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, CONTENTS_CONFIGURATION_NAME), BUCKET).extendsFrom(commonContentsApi)
            .setDescription(description("Implementation dependencies for all packages contents."));

    Configuration commonApi = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, API_CONFIGURATION_NAME), BUCKET)
            .setDescription(description("API dependencies for all packaged artifacts."));
    Configuration commonImplementation = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, IMPLEMENTATION_CONFIGURATION_NAME), BUCKET).extendsFrom(commonApi)
            .setDescription(description("Implementation dependencies for all packaged artifacts."));
    Configuration commonCompileOnlyApi = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, COMPILE_ONLY_API_CONFIGURATION_NAME), BUCKET)
            .setDescription(description("Compile-only API dependencies for all packaged artifacts."));
    Configuration commonRuntimeOnly = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, RUNTIME_ONLY_CONFIGURATION_NAME), BUCKET)
            .setDescription(description("Runtime-only dependencies for all packaged artifacts."));
    Configuration commonProvided = configurations.maybeCreateWithRole(camelPrefix(COMMON_PREFIX, PROVIDED_CONFIGURATION_NAME), BUCKET)
            .setDescription(description("'Provided' API dependencies for all packaged artifacts."));

    configurations.getByName(CONTENTS_API_CONFIGURATION_NAME).extendsFrom(commonContentsApi);
    configurations.getByName(CONTENTS_CONFIGURATION_NAME).extendsFrom(commonContents);
    configurations.getByName(API_CONFIGURATION_NAME).extendsFrom(commonApi);
    configurations.getByName(IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(commonImplementation);
    configurations.getByName(COMPILE_ONLY_API_CONFIGURATION_NAME).extendsFrom(commonCompileOnlyApi);
    configurations.getByName(RUNTIME_ONLY_CONFIGURATION_NAME).extendsFrom(commonRuntimeOnly);
    configurations.getByName(PROVIDED_CONFIGURATION_NAME).extendsFrom(commonProvided);

    configurations.getByName(camelName(CONTENTS_API_CONFIGURATION_NAME)).extendsFrom(commonContentsApi);
    configurations.getByName(camelName(CONTENTS_CONFIGURATION_NAME)).extendsFrom(commonContents);
    configurations.getByName(camelName(API_CONFIGURATION_NAME)).extendsFrom(commonApi);
    configurations.getByName(camelName(IMPLEMENTATION_CONFIGURATION_NAME)).extendsFrom(commonImplementation);
    configurations.getByName(camelName(COMPILE_ONLY_API_CONFIGURATION_NAME)).extendsFrom(commonCompileOnlyApi);
    configurations.getByName(camelName(RUNTIME_ONLY_CONFIGURATION_NAME)).extendsFrom(commonRuntimeOnly);
    configurations.getByName(camelName(PROVIDED_CONFIGURATION_NAME)).extendsFrom(commonProvided);
  }

  @Override
  protected String camelName(String base) {
    return getName() + capitalize(base);
  }

  @Override
  protected String snakeName(String base) {
    if (base.isEmpty()) {
      return getName();
    } else {
      return getName() + "-" + base;
    }
  }

  @Override
  protected String description(String template) {
    return format(template, "the " + getName());
  }
}
