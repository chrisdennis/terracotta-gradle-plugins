package org.terracotta.build.plugins.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.process.internal.ExecException;
import org.jetbrains.annotations.NotNull;
import org.terracotta.build.services.Git;

import java.time.Instant;
import java.util.Objects;

public class BuildInfoPlugin implements Plugin<Project> {

  @Override
  public void apply(@NotNull Project project) {
    final Provider<String> overrideVersion = project.getProviders().gradleProperty("overrideVersion");
    final Provider<String> defaultVersion = project.getProviders().gradleProperty("defaultVersion");
    final Provider<String> version = project.provider(project::getVersion).map(Objects::toString);
    final Provider<Git> git = Git.getOrInstall(project);
    final BuildInfo extension = project.getExtensions().create(BuildInfo.class, "buildInfo", BuildInfoExtension.class);

    extension.getBuildTimestamp().convention(Instant.now())
        .finalizeValueOnRead();

    extension.getVersion().convention(overrideVersion
        .orElse(defaultVersion)
        .orElse(version)
        .map(StructuredVersion::parse)
    ).finalizeValueOnRead();

    extension.getHasLocalChange().convention(git.map(g -> {
      try {
        return g.hasLocalChange();
      } catch (ExecException e) {
        return false;
      }
    })).finalizeValueOnRead();

    extension.getBranch().convention(git.map(g -> {
      try {
        return g.getBranch();
      } catch (ExecException e) {
        return null;
      }
    })).finalizeValueOnRead();

    extension.getCommitHash().convention(git.map(g -> {
      try {
        return g.getCommitHash();
      } catch (ExecException e) {
        return null;
      }
    })).finalizeValueOnRead();

    extension.getRevision().convention(git.flatMap(g ->
        extension.getHasLocalChange().flatMap(hasLocalChange ->
            hasLocalChange ?
                extension.getCommitHash().map(commitHash -> {
                  // note: this try-catch is in reality not needed since git.hash and git.diff won't be called if
                  // the git command is not available (in this case, hasLocalChange is false)
                  try {
                    return commitHash + "+" + System.getProperty("user.name") + ":" + g.hash(g.diff(commitHash));
                  } catch (ExecException e) {
                    return null;
                  }
                }) :
                extension.getCommitHash()))
    ).finalizeValueOnRead();

    project.setVersion(extension.getVersion().get());
  }
}
