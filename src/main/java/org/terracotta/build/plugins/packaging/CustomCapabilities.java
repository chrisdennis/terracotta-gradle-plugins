package org.terracotta.build.plugins.packaging;

import org.gradle.api.DomainObjectSet;
import org.gradle.api.artifacts.ModuleDependencyCapabilitiesHandler;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.plugins.JavaPluginExtension;

public interface CustomCapabilities {

  /**
   * The capabilities of this package/variant.
   *
   * @return the capability set
   */
  DomainObjectSet<Capability> getCapabilities();

  /**
   * Declares a capability for this package/variant.
   *
   * @param notation capability notation
   * @see ModuleDependencyCapabilitiesHandler
   */
  void capability(Object notation);
}
