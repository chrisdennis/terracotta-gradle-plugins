package org.terracotta.build.plugins.packaging;

import org.gradle.api.NamedDomainObjectContainer;

public interface PackagingExtension extends Package {

  /**
   * The set of named variant packages.
   *
   * @return named variant packages
   */
  NamedDomainObjectContainer<? extends VariantPackage> getVariants();
}
