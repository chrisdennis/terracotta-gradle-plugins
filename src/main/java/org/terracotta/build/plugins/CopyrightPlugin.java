package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.resources.TextResource;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.exceptions.MultiCauseException;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.process.ExecSpec;
import org.gradle.process.internal.ExecException;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Integer.parseInt;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.gradle.internal.Actions.composite;
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP;
import static org.terracotta.build.ExecUtils.execUnder;
import static org.terracotta.build.PluginUtils.capitalize;

/**
 * Copyright header enforcement plugin.
 * <p>
 * Ensure all source files have the correct copyright header by creating a bunch of correctly configured checkstyle
 * tasks, and gluing them all together using the right task dependencies.
 */
public class CopyrightPlugin implements Plugin<Project> {

  private static final String[] IGNORED_PATTERNS = new String[]{
          "**/*.jks", "**/*.cer", "**/*.csr",
          "**/*.json", "**/*.tson",
          "**/*.frs", "**/*.lck",
          "**/*.gz", "**/*.zip",
          "**/*.csv",
          "**/*.ico", "**/*.png", "**/*.gif",
          "**/license.xml"
  };

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(CheckstylePlugin.class);

    TextResource copyrightConfig = project.getRootProject().getResources().getText().fromFile("config/checkstyle/copyright.xml");
    TaskCollection<CopyrightHeaderCheck> headerChecks = project.getTasks().withType(CopyrightHeaderCheck.class);
    headerChecks.configureEach(task -> {
      task.setConfig(copyrightConfig);
      task.setClasspath(project.files());
      task.getOutputs().upToDateWhen(Specs.satisfyAll());
    });
    TaskCollection<CopyrightUpdateCheck> updateChecks = project.getTasks().withType(CopyrightUpdateCheck.class);
    updateChecks.configureEach(task -> {
      task.getPattern().value(Pattern.compile("copyright\\s+(?:\\(c\\)|\u00a9)\\s+(?:(?:\\d{4}-)?\\d{4},\\s*)*(?:\\d{4}-)?(?<end>\\d{4})\\s+", CASE_INSENSITIVE));
      task.getEndYear().value(matcher -> parseInt(matcher.group("end")));
    });

    TaskProvider<Task> centralTask = project.getTasks().register("copyright", task -> {
      task.setDescription("Run copyright enforcement");
      task.setGroup(VERIFICATION_GROUP);
      task.dependsOn(headerChecks, updateChecks);
    });

    project.getPlugins().withType(LifecycleBasePlugin.class).configureEach(plugin ->
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, task -> task.dependsOn(centralTask))
    );

    Provider<File> buildDirectory = project.getLayout().getBuildDirectory().getAsFile();
    project.getPlugins().withType(JvmEcosystemPlugin.class).configureEach(plugin ->
            project.getExtensions().configure(SourceSetContainer.class, sourceSets -> sourceSets.configureEach(sourceSet ->
                    sourceSet.getExtensions().add("copyright", createCopyrightSet(project, sourceSet.getName(), copyright -> {
                      copyright.check(sourceSet.getAllSource().getSourceDirectories()
                              .filter(f -> !f.getAbsolutePath().startsWith(buildDirectory.get().getAbsolutePath())));
                    })))));

    project.getExtensions().add("copyright", createCopyrightSet(project, "", copyright -> {}));
  }

  public CopyrightExtension createCopyrightSet(Project project, String name, Action<CopyrightExtension> action) {
    CopyrightExtension copyrightExtension = new CopyrightExtension(project.getObjects());
    copyrightExtension.exclude(IGNORED_PATTERNS);

    project.getTasks().register("copyright" + capitalize(name) + "Header", CopyrightHeaderCheck.class, task -> {
      task.setSource(copyrightExtension.getFiles());
    });

    project.getTasks().register("copyright" + capitalize(name) + "Update", CopyrightUpdateCheck.class, task -> {
      task.setSource(copyrightExtension.getFiles());
      task.getOutputs().upToDateWhen(Specs.satisfyAll());
    });

    action.execute(copyrightExtension);

    return copyrightExtension;
  }

  public static class CopyrightExtension {

    private final ConfigurableFileCollection files;
    private final PatternSet patternSet;

    @Inject
    public CopyrightExtension(ObjectFactory objectFactory) {
      this.files = objectFactory.fileCollection();
      this.patternSet = objectFactory.newInstance(PatternSet.class);
    }

    public FileTree getFiles() {
      return files.getAsFileTree().matching(patternSet);
    }

    public void check(Object ... sources) {
      files.from(sources);
    }

    public void exclude(String ... excludes) {
      patternSet.exclude(excludes);
    }
  }

  public static abstract class CopyrightHeaderCheck extends Checkstyle {}

  public static abstract class CopyrightUpdateCheck extends SourceTask {

    public CopyrightUpdateCheck() {
      getGitExecutable().convention("git");
    }

    @Input
    public abstract Property<String> getGitExecutable();

    @Input
    public abstract Property<Pattern> getPattern();

    @Internal
    public abstract Property<ToIntFunction<Matcher>> getEndYear();

    private static final Pattern PORCELAIN_Z_STATUS_LINE = Pattern.compile("[ MTADRCU?]{2} (?<file>[^\u0000]+)(?:\u0000(?![ MTADRCU?]{2} )(?<from>[^\u0000]+))?\u0000");

    private static Stream<MatchResult> matches(Pattern pattern, CharSequence input) {
      Stream.Builder<MatchResult> builder = Stream.builder();
      Matcher matcher = pattern.matcher(input);
      while (matcher.find()) {
        builder.accept(matcher.toMatchResult());
      }
      return builder.build();
    }

    @TaskAction
    public void checkModifiedCopyrights() {
      Set<File> sourceFiles = getSource().getFiles();

      Path root = sourceFiles.stream().map(File::toPath).reduce((a, b) -> {
        while (!b.startsWith(a)) {
          a = a.getParent();
        }
        return a;
      }).orElseThrow(GradleException::new);

      Map<File, Integer> expectedCopyrightYears = Stream.concat(Stream.of(tryGit(
                              spec -> spec.args("rev-list", "--no-commit-header",
                                      "--pretty=format:%H %ad %(trailers:key=Copyright-Check,valueonly,separator= )", "--date=format:%Y",
                                      "--no-merges", "HEAD", "--not", "--remotes=*/main", "--remotes=*/release/*", root),
                              //Earlier git versions don't support --no-commit-header, so we'll try again without
                              spec -> spec.args("rev-list",
                                      "--pretty=format:%H %ad %(trailers:key=Copyright-Check,valueonly,separator= )", "--date=format:%Y",
                                      "--no-merges", "HEAD", "--not", "--remotes=*/main", "--remotes=*/release/*", root)).split("\\R"))
                      .filter(line -> !line.isEmpty() && !line.startsWith("commit")).flatMap(line -> {
                        String[] fields = line.split("\\s+", 3);
                        String commit = fields[0];
                        int year = parseInt(fields[1]);
                        if (fields.length > 2 && fields[2].equalsIgnoreCase("false")) {
                          getLogger().debug("Skipping copyright update checks for: " + git(spec -> spec.args("show", "--oneline", "--no-patch", commit)));
                          return Stream.empty();
                        } else {
                          return Stream.of(git(spec -> spec.args("diff-tree", "-z", "--no-commit-id", "--name-only", "-r",
                                          "--find-renames=100%", "--find-copies=100%",  "--diff-filter=cr", commit)).split("\0"))
                                  .map(getProject().getRootProject()::file).filter(sourceFiles::contains).map(file -> new AbstractMap.SimpleImmutableEntry<>(file, year));
                        }
                      }), matches(PORCELAIN_Z_STATUS_LINE, git(spec -> spec.args("status", "-z", "--porcelain", "--untracked-files=all", root)))
                      .map(line -> line.group(1)).map(getProject().getRootProject()::file).filter(sourceFiles::contains)
                      .map(file -> new AbstractMap.SimpleImmutableEntry<>(file, Instant.now().atZone(ZoneId.systemDefault()).get(ChronoField.YEAR))))
              .collect(Collectors.toMap(Entry::getKey, Entry::getValue, Math::max));

      Set<File> violations = checkFiles(expectedCopyrightYears);

      if (!violations.isEmpty()) {
        throw new IllegalStateException("Copyright statements are incorrect in:\n\t" + violations.stream().map(File::toString).collect(Collectors.joining("\n\t")));
      }
    }

    private Set<File> checkFiles(Map<File, Integer> expectedUpdates) {
      Pattern pattern = getPattern().get();
      ToIntFunction<Matcher> endYear = getEndYear().get();

      return expectedUpdates.entrySet().stream().filter(update -> update.getKey().isFile()).map(update -> {
        try (Stream<String> lines = Files.lines(update.getKey().toPath(), StandardCharsets.UTF_8)) {
          if (lines.map(pattern::matcher).filter(Matcher::find).anyMatch(matcher -> update.getValue().equals(endYear.applyAsInt(matcher)))) {
            return null;
          } else {
            return update.getKey();
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private String git(Action<ExecSpec> action) {
      return execUnder(this, composite(exec -> exec.setExecutable(getGitExecutable().get()), action));
    }

    @SafeVarargs
    private final String tryGit(Action<ExecSpec>... executions) {
      List<GradleException> failures = new ArrayList<>();
      for (Action<ExecSpec> execution : executions) {
        try {
          return git(execution);
        } catch (ExecException e) {
          failures.add(e);
          getLogger().debug("git execution failed, trying again: ", e);
        }
      }
      throw failures.stream().reduce((a, b) -> {
        a.addSuppressed(b);
        return a;
      }).orElseThrow(AssertionError::new);
    }
  }
}
