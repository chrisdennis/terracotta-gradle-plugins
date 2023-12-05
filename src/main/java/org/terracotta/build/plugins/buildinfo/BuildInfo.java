package org.terracotta.build.plugins.buildinfo;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public interface BuildInfo {
  Property<Boolean> getHasLocalChange();

  Property<String> getBranch();

  Property<String> getCommitHash();

  Property<String> getRevision();

  Property<StructuredVersion> getVersion();

  Property<Instant> getBuildTimestamp();

  default Provider<String> getBuildTimestampFormatted(String pattern, ZoneId zoneId) {
    return getBuildTimestamp().map(ts -> DateTimeFormatter.ofPattern(pattern).withZone(zoneId).format(ts));
  }

  default Provider<String> getBuildTimestampISO8601() {
    return getBuildTimestamp().map(DateTimeFormatter.ISO_INSTANT::format);
  }

  default Provider<String> getBuildTimestampForBuildData() {
    return getBuildTimestampFormatted("uuuu-MM-dd 'at' HH:mm:ss z", ZoneId.systemDefault());
  }
}
