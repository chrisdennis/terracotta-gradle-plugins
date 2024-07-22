package org.terracotta.build.services;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecSpec;
import org.gradle.process.internal.ExecException;
import org.terracotta.build.ExecUtils;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Arrays.asList;
import static org.gradle.internal.Actions.composite;

public abstract class Git implements BuildService<Git.Parameters> {
  public interface Parameters extends BuildServiceParameters {
    Property<String> getGitExecutable();
    Property<File> getWorkingDir();
  }

  @Inject
  public abstract ExecOperations getExecOperations();

  public boolean hasLocalChange() throws ExecException {
    return !execute(spec -> spec.args("status", "--porcelain=2")).isEmpty();
  }

  public String getCommitHash() throws ExecException {
    return Optional.ofNullable(System.getenv("GIT_COMMIT"))
        .orElseGet(() -> execute(spec -> spec.args("rev-parse", "HEAD")));
  }

  public String getBranch() throws ExecException {
    return Optional.ofNullable(System.getenv("GIT_BRANCH"))
        .orElseGet(() -> execute(spec -> spec.args("rev-parse", "--abbrev-ref", "HEAD")));
  }

  public String diff(String commitHash) throws ExecException {
    return execute(spec -> spec.args("diff", commitHash, "."));
  }

  public String hash(String data) throws ExecException {
    return execute(spec -> {
      spec.args("hash-object", "--stdin");
      spec.setStandardInput(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    });
  }

  public String execute(Action<ExecSpec> action) throws ExecException {
    return ExecUtils.execute(getExecOperations(), composite(spec -> {
      spec.executable(getParameters().getGitExecutable().get());
      spec.setWorkingDir(getParameters().getWorkingDir().get());
      spec.args("--no-pager");
    }, action));
  }

  public String executeOrFallback(Action<ExecSpec> action, Action<ExecSpec>... fallbacks) throws ExecException {
    List<GradleException> failures = new ArrayList<>();
    List<Action<ExecSpec>> executions = new ArrayList<>(fallbacks.length + 1);
    executions.add(action);
    executions.addAll(asList(fallbacks));
    for (Action<ExecSpec> execution : executions) {
      try {
        return execute(execution);
      } catch (ExecException e) {
        failures.add(e);
      }
    }
    throw failures.stream().reduce((a, b) -> {
      a.addSuppressed(b);
      return a;
    }).orElseThrow(AssertionError::new);
  }

  public static Provider<Git> getOrInstall(Project project) {
    return project.getGradle().getSharedServices().registerIfAbsent("git", Git.class, spec -> spec.parameters(parameters -> {
      parameters.getGitExecutable().set("git");
      parameters.getWorkingDir().set(project.getRootDir());
    }));
  }
}
