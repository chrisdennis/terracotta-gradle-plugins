plugins {
  id("java-gradle-plugin")
}

group = "org.terracotta.build"

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  api("gradle.plugin.com.github.johnrengelman:shadow:7.1.2")
  api("biz.aQute.bnd:biz.aQute.bndlib:6.4.0")
  implementation("org.apache.httpcomponents.client5:httpclient5-fluent:5.2.1")
  implementation("org.terracotta:terracotta-utilities-tools:0.0.16")

  testImplementation(platform("org.junit:junit-bom:5.9.1"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testImplementation("org.hamcrest:hamcrest:2.2")
  testImplementation("org.mockito:mockito-inline:4.11.0")
}

gradlePlugin {
  plugins {
    register("angela") {
      id = "org.terracotta.build.angela"
      implementationClass = "org.terracotta.build.plugins.AngelaPlugin"
    }
    register("blacklist") {
      id = "org.terracotta.build.blacklist"
      implementationClass = "org.terracotta.build.plugins.BlacklistPlugin"
    }
    register("copyright") {
      id = "org.terracotta.build.copyright"
      implementationClass = "org.terracotta.build.plugins.CopyrightPlugin"
    }
    register("deploy") {
      id = "org.terracotta.build.deploy"
      implementationClass = "org.terracotta.build.plugins.DeployPlugin"
    }
    register("dockerEcosystem") {
      id = "org.terracotta.build.docker-ecosystem"
      implementationClass = "org.terracotta.build.plugins.docker.DockerEcosystemPlugin"
    }
    register("docker") {
      id = "org.terracotta.build.docker-build"
      implementationClass = "org.terracotta.build.plugins.docker.DockerBuildPlugin"
    }
    register("galvan") {
      id = "org.terracotta.build.galvan"
      implementationClass = "org.terracotta.build.plugins.GalvanPlugin"
    }
    register("javadocFilter") {
      id = "org.terracotta.build.javadoc-annotation"
      implementationClass = "org.terracotta.build.plugins.JavadocAnnotationPlugin"
    }
    register("javaVersionPlugin") {
      id = "org.terracotta.build.java-version"
      implementationClass = "org.terracotta.build.plugins.JavaVersionPlugin"
    }
    register("packaging") {
      id = "org.terracotta.build.package"
      implementationClass = "org.terracotta.build.plugins.PackagePlugin"
    }
    register("voltron") {
      id = "org.terracotta.build.voltron"
      implementationClass = "org.terracotta.build.plugins.VoltronPlugin"
    }
  }
}
