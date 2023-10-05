package org.terracotta.build.plugins;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;

import java.util.List;

import static java.util.Arrays.asList;

public class CustomHostPluginExtension {

  private final MapProperty<String, List<String>> customHosts;

  @SuppressWarnings({"unchecked", "rawtypes"})
  public CustomHostPluginExtension(ObjectFactory objectFactory) {
    this.customHosts = (MapProperty) objectFactory.mapProperty(String.class, List.class);
  }

  public MapProperty<String, List<String>> getCustomHosts() {
    return customHosts;
  }

  public void host(String ip, String ... hosts) {
    getCustomHosts().put(ip, asList(hosts));
  }
}
