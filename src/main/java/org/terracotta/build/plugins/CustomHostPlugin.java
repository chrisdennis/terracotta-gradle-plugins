package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.testing.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CustomHostPlugin implements Plugin<Project> {
  private static final String CUSTOM_HOST_FILE_NAME = "fake-hosts.txt";

  @Override
  public void apply(Project project) {
    DirectoryProperty buildDir = project.getLayout().getBuildDirectory();
    CustomHostPluginExtension customHostPluginExtension = project.getExtensions().create("customHostConfig", CustomHostPluginExtension.class);
    project.getTasks().withType(Test.class, task -> {
      task.doFirst(new Action<Task>() {
        @Override
        public void execute(Task t) {
          List<String> customHostList = customHostPluginExtension.getCustomHosts().get();
          if (!customHostList.isEmpty()) {
            String customHostNames = String.join(" ", customHostList);
            final Path path = buildDir.getAsFile().get().toPath().resolve(CUSTOM_HOST_FILE_NAME);
            try {
              Files.write(path, customHostNames.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
              e.printStackTrace();
            }
            task.systemProperty("jdk.net.hosts.file", path);
          }
        }
      });
    });
  }
}
