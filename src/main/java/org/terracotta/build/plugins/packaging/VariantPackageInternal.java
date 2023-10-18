package org.terracotta.build.plugins.packaging;

import static java.text.MessageFormat.format;
import static org.terracotta.build.PluginUtils.capitalize;

public abstract class VariantPackageInternal extends PackageInternal implements VariantPackage, CustomCapabilitiesInternal {

  @Override
  protected String camelName(String base) {
    return getName() + capitalize(base);
  }

  @Override
  protected String kebabName(String base) {
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
