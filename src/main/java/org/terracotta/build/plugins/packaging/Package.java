package org.terracotta.build.plugins.packaging;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

public interface Package {

  void withSourcesJar();

  default void withJavadocJar() {
    withJavadocJar(javadoc -> {});
  }

  void withJavadocJar(Action<Javadoc> action);

  NamedDomainObjectContainer<? extends OptionalFeature> getOptionalFeatures();

  interface OptionalFeature extends Named, CustomCapabilities {}

  interface Javadoc {

    SetProperty<Dependency> getClasspath();

    Property<Dependency> getArtifact();

    default void classpath(Object dependency) {
      classpath(dependency, d -> {});
    }

    void classpath(Object notation, Action<? super Dependency> action);

    default void from(Object notation) {
      from(notation, d -> {});
    }

    void from(Object notation, Action<? super Dependency> action);
  }
}
