/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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

package org.terracotta.build;

import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.provider.DefaultProperty;
import org.gradle.api.internal.provider.DefaultProvider;
import org.gradle.api.internal.provider.PropertyHost;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UtilsTest {

  @Test
  public void testCannotOverDropFile() {
    RelativePath relativeSourcePath = new RelativePath(true, "bar.txt");
    FileCopyDetails fcd = when(mock(FileCopyDetails.class).getRelativeSourcePath()).thenReturn(relativeSourcePath).getMock();

    assertThrows(IllegalStateException.class, () -> Utils.dropTopLevelDirectories(2).execute(fcd));
  }

  @Test
  public void testCannotDropFileToNoPath() {
    RelativePath relativeSourcePath = new RelativePath(true, "bar.txt");
    FileCopyDetails fcd = when(mock(FileCopyDetails.class).getRelativeSourcePath()).thenReturn(relativeSourcePath).getMock();

    assertThrows(IllegalStateException.class, () -> Utils.dropTopLevelDirectories(1).execute(fcd));
  }

  @Test
  public void testOverDroppedDirectoriesAreExcluded() {
    RelativePath relativeSourcePath = new RelativePath(false, "bar");
    FileCopyDetails fcd = when(mock(FileCopyDetails.class).getRelativeSourcePath()).thenReturn(relativeSourcePath).getMock();

    Utils.dropTopLevelDirectories(1).execute(fcd);

    verify(fcd).exclude();
  }

  @Test
  public void testDropsCorrectly() {
    FileCopyDetails fcd = mock(FileCopyDetails.class);
    RelativePath relativeSourcePath = new RelativePath(true, "foo", "bar.txt");
    when(fcd.getRelativeSourcePath()).thenReturn(relativeSourcePath).getMock();
    RelativePath relativePath = new RelativePath(true, "prefix", "foo", "bar.txt");
    when(fcd.getRelativePath()).thenReturn(relativePath).getMock();

    Utils.dropTopLevelDirectories(1).execute(fcd);

    RelativePath expected = new RelativePath(true, "prefix", "bar.txt");
    verify(fcd).setRelativePath(expected);
  }

  @ParameterizedTest
  @MethodSource
  public void testJaxbRuntimeForOldJava(Object target) {
    Object actual = Utils.jaxbRuntime(target);
    if (actual instanceof Provider<?>) {
      assertThat(((Provider<?>) actual).getOrNull(), is(nullValue()));
    } else {
      assertThat(actual, is(nullValue()));
    }
  }

  public static Stream<Object> testJaxbRuntimeForOldJava() {
    return Stream.of(JavaLanguageVersion.of(6), JavaLanguageVersion.of(7), JavaLanguageVersion.of(8)).flatMap(UtilsTest::mapToJaxbInputObjects);
  }

  @ParameterizedTest
  @MethodSource
  public void testJaxbRuntimeForNewJava(Object target) {
    Object actual = Utils.jaxbRuntime(target);
    if (actual instanceof Provider<?>) {
      assertThat(((Provider<?>) actual).getOrNull(), is("org.glassfish.jaxb:jaxb-runtime"));
    } else {
      assertThat(actual, is("org.glassfish.jaxb:jaxb-runtime"));
    }
  }

  public static Stream<Object> testJaxbRuntimeForNewJava() {
    return Stream.of(JavaLanguageVersion.of(9), JavaLanguageVersion.of(10), JavaLanguageVersion.of(11)).flatMap(UtilsTest::mapToJaxbInputObjects);
  }

  public static Stream<Object> mapToJaxbInputObjects(JavaLanguageVersion version) {
    JavaInstallationMetadata installationMetadata = when(mock(JavaInstallationMetadata.class).getLanguageVersion())
            .thenReturn(version).getMock();
    JavaLauncher javaLauncher = when(mock(JavaLauncher.class).getMetadata())
            .thenReturn(installationMetadata).getMock();

    org.gradle.api.tasks.testing.Test testTask = when(mock(org.gradle.api.tasks.testing.Test.class).getJavaLauncher())
            .thenReturn(new DefaultProperty<>(PropertyHost.NO_OP, JavaLauncher.class).value(javaLauncher)).getMock();

    return Stream.of(version, testTask, javaLauncher).flatMap(v -> Stream.of(v, new DefaultProvider<>(() -> v)));
  }

  @Test
  public void testMapOfStringWithBadParameterCount() {
    assertThrows(IllegalArgumentException.class, () -> Utils.mapOf("foo"));
    assertThrows(IllegalArgumentException.class, () -> Utils.mapOf("foo", "bar", "baz"));
  }

  @Test
  public void testMapOfString() {
    Map<String, Integer> reference = new HashMap<>();
    reference.put("foo", 1);
    reference.put("bar", 2);

    assertThat(Utils.mapOf("foo", 1, "bar", 2), is(equalTo(reference)));
  }

  @Test
  public void testMapOfStringWithMismatchedKeyType() {
    assertThrows(ClassCastException.class, () -> Utils.mapOf(1, 2));
  }

  @Test
  public void testMapOfWithBadParameterCount() {
    assertThrows(IllegalArgumentException.class, () -> Utils.mapOf(Integer.class, Integer.class, 1));
    assertThrows(IllegalArgumentException.class, () -> Utils.mapOf(Integer.class, Integer.class, 1, 2, 3));
  }

  @Test
  public void testMapOf() {
    Map<Integer, Integer> reference = new HashMap<>();
    reference.put(1, 2);
    reference.put(3, 4);

    assertThat(Utils.mapOf(Integer.class, Integer.class, 1, 2, 3, 4), is(equalTo(reference)));
  }

  @Test
  public void testMapOfWithMismatchedKeyType() {
    assertThrows(ClassCastException.class, () -> Utils.mapOf(Integer.class, Integer.class, "foo", 2));
  }

  @Test
  public void testMapOfWithMismatchedValueType() {
    assertThrows(ClassCastException.class, () -> Utils.mapOf(Integer.class, Integer.class, 1, "foo"));
  }

  @Test
  public void testArtifactMap() {
    assertThat(Utils.artifact("foo", "bar"), allOf(
            hasEntry("group", "foo"),
            hasEntry("name", "bar")
    ));
  }

  @Test
  public void testGroupMap() {
    assertThat(Utils.group("foo"), hasEntry("group", "foo"));
  }

  @Test
  public void testPathOnlyCoordinateMap() {
    assertThat(Utils.coordinate("foo"), hasEntry("path", "foo"));
  }

  @Test
  public void testCoordinateMap() {
    assertThat(Utils.coordinate("foo", "bar"), allOf(
            hasEntry("path", "foo"),
            hasEntry("configuration", "bar")
    ));
  }
}
