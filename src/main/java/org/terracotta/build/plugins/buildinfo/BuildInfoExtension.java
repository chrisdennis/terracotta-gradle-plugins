package org.terracotta.build.plugins.buildinfo;

import org.gradle.api.provider.Property;

import java.time.Instant;

public abstract class BuildInfoExtension implements BuildInfo {
  @Override
  public abstract Property<Boolean> getHasLocalChange();

  @Override
  public abstract Property<String> getBranch();

  @Override
  public abstract Property<String> getCommitHash();

  @Override
  public abstract Property<String> getRevision();

  @Override
  public abstract Property<StructuredVersion> getVersion();

  @Override
  public abstract Property<Instant> getBuildTimestamp();
}
