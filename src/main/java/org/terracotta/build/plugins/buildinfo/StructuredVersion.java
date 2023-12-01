package org.terracotta.build.plugins.buildinfo;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

public class StructuredVersion implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final Pattern SEPARATOR = Pattern.compile("[.-]");
  private static final Pattern SPLIT = Pattern.compile("(?=" + SEPARATOR.pattern() + ")|(?<=" + SEPARATOR.pattern() + ")");

  private final String version;
  private final List<String> components;

  private StructuredVersion(String version) {
    this.version = version;
    this.components = asList(SPLIT.split(version));
    if (!components.isEmpty() && SEPARATOR.matcher(components.get(0)).matches()) {
      throw new IllegalArgumentException(version + " starts with a separator: " + SEPARATOR.pattern());
    }
  }

  public String length(int length) {
    if (length < 1) {
      throw new IllegalArgumentException("Cannot return version with " + length + " components");
    } else {
      return components.stream().limit((length * 2L) - 1).collect(joining());
    }
  }

  public String toString() {
    return version;
  }

  public String getAt(int index) {
    return components.get(index * 2);
  }

  public static StructuredVersion parse(String version) {
    return new StructuredVersion(version);
  }
}
