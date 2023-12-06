package org.terracotta.build.plugins.buildinfo;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public interface BuildInfoExtension {
  Property<Boolean> getHasLocalChange();

  Property<String> getBranch();

  Property<String> getCommitHash();

  Property<String> getRevision();

  Property<StructuredVersion> getVersion();

  Property<ZonedDateTime> getBuildTimestamp();

  default Provider<String> getBuildTimestampISO8601() {
    return getBuildTimestamp().map(DateTimeFormatter.ISO_INSTANT::format);
  }
}
