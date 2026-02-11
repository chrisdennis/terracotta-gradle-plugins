/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class StructuredVersionTest {

  public static Stream<Arguments> validVersions() {
      return Stream.of(
              arguments("", List.of("")),
              arguments("1", List.of("1")),
              arguments("1.2", List.of("1", "2")),
              arguments("1.2.3", List.of("1", "2", "3")),
              arguments("1.2.3-SNAPSHOT", List.of("1", "2", "3", "SNAPSHOT")),
              arguments("1-2-3", List.of("1", "2", "3")),
              arguments("1..2.3", List.of("1", "2", "3")),
              arguments("version", List.of("version"))
      );
  }

  public static Stream<Arguments> orderedVersionPairs() {
    return Stream.of(
            arguments("1", "2"),
            arguments("1.1", "1.2"),
            arguments("1.1", "2"),
            arguments("1", "1.1"),
            arguments("1", "1-SNAPSHOT"),
            arguments("1.1", "1-SNAPSHOT"),
            arguments("2", "10"),
            arguments("1.0.0-alpha", "1.0.0-beta")

    );
  }

  @ParameterizedTest(name = "Valid version: {0}")
  @MethodSource("validVersions")
  void testValidVersionParsing(String version, List<String> components) {
    StructuredVersion parsed = StructuredVersion.parse(version);

    assertThat(parsed.toString(), is(version));
    assertThat(parsed, contains(components.toArray(String[]::new)));
    for (int i = 0; i < components.size(); i++) {
      assertThat(parsed.getAt(i), is(components.get(i)));
    }

    for (int i = 0; i < components.size(); i++) {
      assertThat(parsed.length(i), stringContainsInOrder(components.subList(0, i)));
    }
    assertThat(parsed.length(components.size() + 1), stringContainsInOrder(components));
  }

  @ParameterizedTest(name = "Invalid version starting with separator: {0}")
  @ValueSource(strings = {".1.0.0", "-1.0.0"})
  void testInvalidVersionStartingWithSeparator(String version) {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> StructuredVersion.parse(version));
    assertThat(exception.getMessage(), containsString("starts with a separator"));
  }

  @Test
  void testGetAtOutOfBounds() {
    StructuredVersion version = StructuredVersion.parse("1.2.3");

    assertThrows(IndexOutOfBoundsException.class, () -> version.getAt(3));
    assertThrows(IndexOutOfBoundsException.class, () -> version.getAt(-1));
  }

  @Test
  void testLengthOutOfBounds() {
    StructuredVersion version = StructuredVersion.parse("1.2.3");

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> version.length(-1));
    assertThat(exception.getMessage(), containsString("Cannot return version with"));
  }

  // ========== compareTo Tests ==========

  @ParameterizedTest(name = "{0} is less than {1}")
  @MethodSource("orderedVersionPairs")
  void testCompareTo(String version1, String version2) {
    StructuredVersion v1 = StructuredVersion.parse(version1);
    StructuredVersion v2 = StructuredVersion.parse(version2);
    
    assertThat(v1.compareTo(v2), lessThan(0));
    assertThat(v2.compareTo(v1), greaterThan(0));
  }

  @ParameterizedTest(name = "{0} is equal to itself")
  @MethodSource("validVersions")
  void testEqualVersions(String version) {
    StructuredVersion v1 = StructuredVersion.parse(version);
    StructuredVersion v2 = StructuredVersion.parse(version);

    assertThat(v1.compareTo(v2), is(0));
    assertThat(v2.compareTo(v1), is(0));
  }

  @Test
  void testEqualVersionsMismatchedSeparators() {
    StructuredVersion v1 = StructuredVersion.parse("1.2.3");
    StructuredVersion v2 = StructuredVersion.parse("1-2-3");

    assertThat(v1.compareTo(v2), is(0));
    assertThat(v2.compareTo(v1), is(0));
  }
}