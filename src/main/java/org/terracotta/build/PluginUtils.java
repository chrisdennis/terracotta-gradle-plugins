package org.terracotta.build;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.util.Locale;

public class PluginUtils {

  public static String capitalize(String word) {
    if (word.isEmpty()) {
      return word;
    } else {
      return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
  }

}
