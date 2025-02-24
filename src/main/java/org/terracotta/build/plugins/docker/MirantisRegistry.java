package org.terracotta.build.plugins.docker;

import static org.terracotta.build.PluginUtils.capitalize;

public interface MirantisRegistry extends DockerRegistry {

  default String getReadmePushTaskName() {
    return "dockerPushReadmeTo" + capitalize(getName());
  }
}
