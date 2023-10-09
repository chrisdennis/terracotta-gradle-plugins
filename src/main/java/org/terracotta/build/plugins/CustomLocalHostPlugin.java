package org.terracotta.build.plugins;

import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static org.terracotta.build.PluginUtils.capitalize;

public class CustomLocalHostPlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    DirectoryProperty buildDir = project.getLayout().getBuildDirectory();

    project.getTasks().withType(Test.class, task -> {
      CustomHostPluginExtension customHostPluginExtension = task.getExtensions().create("hosts", CustomHostPluginExtension.class, project.getObjects());

      TaskProvider<WriteHostsFile> writeHostsFile = project.getTasks().register(hostsTaskName(task), WriteHostsFile.class, hostsFileTask -> {
        hostsFileTask.getHostsFile().convention(buildDir.file("hosts/" + task.getName()));
        hostsFileTask.getHostsConfiguration().convention(customHostPluginExtension.getCustomLocalHosts());
        hostsFileTask.onlyIf(t -> !hostsFileTask.getHostsConfiguration().get().isEmpty());
      });
      task.dependsOn(writeHostsFile);
      task.getJvmArgumentProviders().add(() -> {
        if (writeHostsFile.get().isEnabled()) {
          return singleton("-Djdk.net.hosts.file=" + writeHostsFile.get().getHostsFile().getAsFile().get().getAbsolutePath());
        } else {
          return emptyList();
        }
      });
    });
  }

  public static String hostsTaskName(Test task) {
    return "writeHostsFile" + capitalize(task.getName());
  }

  static abstract class WriteHostsFile extends DefaultTask {

    @Inject
    public WriteHostsFile() {
    }

    @OutputFile
    public abstract RegularFileProperty getHostsFile();

    @Input
    public abstract ListProperty<String> getHostsConfiguration();

    @TaskAction
    public void generateHostsFile() throws IOException {
      RegularFile hostsFile = getHostsFile().get();

      try (BufferedWriter writer = Files.newBufferedWriter(hostsFile.getAsFile().toPath(), StandardCharsets.UTF_8)) {
        writer.append(String.join(" ", getHostsConfiguration().get()));
      }
    }
  }
}
