package org.terracotta.build.plugins.packaging;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.tasks.Nested;

public interface PackagingExtensionInternal extends PackagingExtension {

  @Override
  default void withSourcesJar() {
    getDefaultPackage().withSourcesJar();
  }

  @Override
  default void withJavadocJar() {
    getDefaultPackage().withJavadocJar();
  }

  @Override
  default void withJavadocJar(Action<Javadoc> action) {
    getDefaultPackage().withJavadocJar(action);
  }

  @Override
  default NamedDomainObjectContainer<? extends OptionalFeature> getOptionalFeatures() {
    return getDefaultPackage().getOptionalFeatures();
  }

  @Nested
  DefaultPackageInternal getDefaultPackage();

  @Override
  NamedDomainObjectContainer<VariantPackageInternal> getVariants();
}
