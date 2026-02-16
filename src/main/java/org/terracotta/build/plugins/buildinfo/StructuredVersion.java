/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terracotta.build.plugins.buildinfo;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

public class StructuredVersion implements Serializable, Comparable<StructuredVersion>, Iterable<String> {
  private static final long serialVersionUID = 1L;
  private static final Pattern SEPARATOR = Pattern.compile("[.-]");
  private static final Pattern SPLIT = Pattern.compile("(?<!" + SEPARATOR.pattern() + ")(?=" + SEPARATOR.pattern() + ")|(?<=" + SEPARATOR.pattern() + ")(?!" + SEPARATOR.pattern() + ")");


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
    if (length < 0) {
      throw new IllegalArgumentException("Cannot return version with " + length + " components");
    } else if (length == 0) {
      return "";
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

  @Override
  public Iterator<String> iterator() {
    return IntStream.range(0, (components.size() + 1) / 2).mapToObj(this::getAt).iterator();
  }

  @Override
  public int compareTo(StructuredVersion o) {
    Iterator<String> thisIter = this.iterator();
    Iterator<String> otherIter = o.iterator();

    while (thisIter.hasNext() && otherIter.hasNext()) {
      String thisComponent = thisIter.next();
      String otherComponent = otherIter.next();

      int result = compareComponents(thisComponent, otherComponent);
      if (result != 0) {
        return result;
      }
    }

    return Boolean.compare(thisIter.hasNext(), otherIter.hasNext());
  }

  private static int compareComponents(String a, String b) {
    try {
      int i = Integer.parseInt(a);
      int j = Integer.parseInt(b);
      return Integer.compare(i, j);
    } catch (NumberFormatException e) {
      return a.compareTo(b);
    }
  }
}