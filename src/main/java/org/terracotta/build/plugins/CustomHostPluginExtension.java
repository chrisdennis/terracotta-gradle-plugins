package org.terracotta.build.plugins;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;

public class CustomHostPluginExtension {

  private final ListProperty<String> customHosts;

  @SuppressWarnings({"unchecked", "rawtypes"})
  public CustomHostPluginExtension(ObjectFactory objectFactory) {
    this.customHosts = objectFactory.listProperty(String.class);
  }

  public ListProperty<String> getCustomLocalHosts() {
    return customHosts;
  }
}
