package org.terracotta.build.plugins.packaging;

import org.gradle.api.DomainObjectSet;
import org.gradle.api.capabilities.Capability;

public interface CustomCapabilities {

  DomainObjectSet<Capability> getCapabilities();

  void capability(Object notation);
}
