package org.terracotta.build.plugins.packaging;

public abstract class DefaultPackageInternal extends PackageInternal {

  @Override
  protected String camelName(String base) {
    return base;
  }

  @Override
  protected String kebabName(String base) {
    return base;
  }

  @Override
  protected String description(String template) {
    return null;
  }
}
