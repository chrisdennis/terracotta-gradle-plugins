package org.terracotta.build.plugins.packaging;

import org.gradle.api.NamedDomainObjectContainer;

public interface PackagingExtension extends Package {

  NamedDomainObjectContainer<? extends VariantPackage> getVariants();
}
