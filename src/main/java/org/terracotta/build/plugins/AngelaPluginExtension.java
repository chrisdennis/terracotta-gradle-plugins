package org.terracotta.build.plugins;

import org.gradle.api.provider.ListProperty;

public interface AngelaPluginExtension {
  ListProperty<String> getSpawnedProcessConfig();
}
