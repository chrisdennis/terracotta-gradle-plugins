package org.terracotta.build.plugins.docker;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public abstract class DockerRmi extends DockerTask {

  public DockerRmi() {
    getFilters().addAll(getMetadata().map(metadata -> metadata.entrySet().stream()
            .map(e -> "label=" + e.getKey() + "=" + e.getValue()).collect(toList())));
  }

  @TaskAction
  public void rmi() {
    String images = docker(spec -> spec.args("images", "--quiet").args(getFilters().get().stream().flatMap(filter -> Stream.of("--filter", filter)).toArray())).trim();
    if (!images.isEmpty()) {
      docker(spec -> spec.args("rmi").args(getArguments().get()).args((Object[]) images.split("\\R")));
    }
  }

  @Input
  public abstract ListProperty<String> getFilters();

  @Input
  public abstract ListProperty<String> getArguments();

  @Input
  public abstract MapProperty<String, String> getMetadata();
}
