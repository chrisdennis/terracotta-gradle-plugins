package org.terracotta.build.plugins;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

public class JavaVersionPlugin implements Plugin<Project> {

  public static final JavaLanguageVersion DEFAULT_LANGUAGE_VERSION = JavaLanguageVersion.of(8);

  @Override
  public void apply(Project project) {
    JavaVersions javaVersions = project.getExtensions().create("java-versions", JavaVersions.class);

    javaVersions.getCompileVersion().convention(project.provider(() -> project.findProperty("compileVM"))
            .map(o -> JavaLanguageVersion.of(o.toString()))
            .orElse(DEFAULT_LANGUAGE_VERSION));

    javaVersions.getTestVersion().convention(project.provider(() -> project.findProperty("testVM"))
            .map(o -> JavaLanguageVersion.of(o.toString()))
            .orElse(javaVersions.getCompileVersion()));
  }

  public interface JavaVersions {

    Property<JavaLanguageVersion> getCompileVersion();

    Property<JavaLanguageVersion> getTestVersion();
  }
}
