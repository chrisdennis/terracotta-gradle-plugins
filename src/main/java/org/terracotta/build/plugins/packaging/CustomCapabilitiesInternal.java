package org.terracotta.build.plugins.packaging;

import org.gradle.api.Project;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParser;

import javax.inject.Inject;

import static org.terracotta.build.Utils.mapOf;

public interface CustomCapabilitiesInternal extends CustomCapabilities {

  @Inject
  Project getProject();

  @Inject
  CapabilityNotationParser getCapabilityNotationParser();

  @Override
  default void capability(Object notation) {
    Capability c = getCapabilityNotationParser().parseNotation(notation);
    if (c.getVersion() == null) {
      c = getCapabilityNotationParser().parseNotation(mapOf(
              "group", c.getGroup(),
              "name", c.getName(),
              "version", getProject().getVersion()));
    }
    getCapabilities().add(c);
  }
}
