/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terracotta.build.plugins.docker;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.Category;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.terracotta.build.plugins.CopyrightPlugin;
import org.terracotta.build.plugins.buildinfo.BuildInfoExtension;
import org.terracotta.build.plugins.buildinfo.BuildInfoPlugin;
import org.terracotta.build.plugins.docker.DockerEcosystemPlugin.DockerExtension;

import java.io.File;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.plus;
import static org.terracotta.build.PluginUtils.capitalize;
import static org.terracotta.build.Utils.getLocalHostName;
import static org.terracotta.build.Utils.mapOf;
import static org.terracotta.build.plugins.docker.DockerEcosystemPlugin.DOCKER_IMAGE_ID;

public class DockerBuildPlugin implements Plugin<Project> {

  private static final Pattern DOCKERFILE_EVAL_PATTERN = Pattern.compile("\\$\\$\\{(?<expression>.+)}");

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BuildInfoPlugin.class);
    project.getPlugins().apply(DockerEcosystemPlugin.class);

    DockerExtension dockerExtension = project.getExtensions().getByType(DockerExtension.class);

    DockerBuildExtension buildExtension = ((ExtensionAware) dockerExtension).getExtensions().create("build", DockerBuildExtension.class);

    BuildInfoExtension buildInfo = project.getExtensions().getByType(BuildInfoExtension.class);

    configureBuildDefaults(project, buildExtension, buildInfo);

    TaskProvider<Sync> dockerEnvironment = project.getTasks().register("dockerEnvironment", Sync.class, sync -> {
      sync.setDescription("Assembles the Docker build environment.");
      sync.setGroup(LifecycleBasePlugin.BUILD_GROUP);
      sync.getInputs().property("projectVersion", (Callable<Object>) project::getVersion);
      sync.getInputs().property("dockerImageIds", dockerExtension.getImages().all());

      sync.into(project.getLayout().getBuildDirectory().dir("docker"));
      sync.from(buildExtension.getContentsDirectory()).with(buildExtension.getContents());

      sync.from(buildExtension.getDockerFile(), spec -> spec.filter(inputLine -> {
        Matcher evalMatches = DOCKERFILE_EVAL_PATTERN.matcher(inputLine);
        StringBuffer sb = new StringBuffer();
        while (evalMatches.find()) {
          evalMatches.appendReplacement(sb, limitedEval(project.getVersion(), dockerExtension,
                  "\"${" + evalMatches.group("expression") + "}\"").toString());
        }
        evalMatches.appendTail(sb);

        return sb.toString();
      }));
    });

    Provider<RegularFile> imageIdFile = project.getLayout().getBuildDirectory().file(project.getName() + ".iid");

    TaskProvider<DockerBuild> dockerBuild = project.getTasks().register("dockerBuild", DockerBuild.class, build -> {
      build.setDescription("Build Docker images.");
      build.getEnvironment().fileProvider(dockerEnvironment.map(Sync::getDestinationDir));
      build.getDockerfile().fileProvider(buildExtension.getDockerFile().zip(dockerEnvironment,
              (dockerFile, environment) -> new File(environment.getDestinationDir(), dockerFile.getAsFile().getName())));
      build.getImageIdFile().set(imageIdFile);
      build.getMetadata().set(buildExtension.getMetadata());
      build.getBuildArgs().set(buildExtension.getBuildArgs());
    });

    TaskProvider<Sync> dockerProcessReadme = project.getTasks().register("dockerProcessReadme", Sync.class, sync -> {
      sync.setDescription("Process Docker README.");
      sync.from(buildExtension.getDocTemplates(), buildExtension.getDockerReadme());
      sync.into(project.getLayout().getBuildDirectory().dir("docker-readme"));

      sync.getInputs().property("projectVersion", (Callable<Object>) project::getVersion);
      sync.expand(plus(
          buildExtension.getDocMetadata().get(),
          mapOf("version", project.getVersion(), "root", project.getLayout().getBuildDirectory().dir("docker-readme"))));
    });

    project.getTasks().register("dockerProcessSagDoc", Sync.class, sync -> {
      sync.setDescription("Process SAG Doc.");
      sync.from(buildExtension.getDocTemplates(), buildExtension.getDockerReadme(), buildExtension.getSagDocDirectory());
      sync.into(project.getLayout().getBuildDirectory().dir("docker-sag-doc"));

      sync.getInputs().property("projectVersion", (Callable<Object>) project::getVersion);
      sync.expand(plus(
          buildExtension.getDocMetadata().get(),
          mapOf("version", project.getVersion(), "root", project.getLayout().getBuildDirectory().dir("docker-sag-doc"))));
    });

    project.getTasks().register("dockerTag", t -> {
      t.dependsOn(project.getTasks().withType(DockerTag.class));
      t.setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
      t.setDescription("Create all Docker image tags, local and remote.");
    });
    project.getTasks().register("dockerPush", t -> {
      t.dependsOn(project.getTasks().withType(DockerPush.class));
      t.setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
      t.setDescription("Push all tagged Docker images to all remotes");
    });
    project.getTasks().register("dockerPushReadme", t -> {
      t.dependsOn(project.getTasks().withType(MirantisPushReadme.class));
      t.setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
      t.setDescription("Push all new Docker repository descriptions to all remotes");
    });

    ExtensiblePolymorphicDomainObjectContainer<Registry> registries = buildExtension.getRegistries();
    registries.all(registry -> {
      project.getTasks().register(registry.getTagTaskName(), DockerTag.class, tag -> {
        tag.setDescription("Tag Docker images in preparation for pushing to " + registry.getName());

        Provider<String> registryPrefix = registry.getUri().zip(registry.getOrganization(), (uri, org) -> {
          URI namespace = uri.resolve(org);
          return namespace.getAuthority() + namespace.getPath() + "/";
        });
        Provider<String> tagPrefix = registryPrefix.zip(buildExtension.getImageName(), (reg, name) -> reg + name);

        tag.getTags().set(tagPrefix.zip(buildExtension.getTags(), (prefix, tags) -> tags.stream().map(t -> prefix + ":" + t).collect(toList())).orElse(emptyList()));
        tag.getImageId().set(dockerBuild.flatMap(DockerBuild::getImageId));
      });
    });
    registries.registerBinding(DockerRegistry.class, DockerRegistry.class);
    registries.withType(DockerRegistry.class).all(registry -> {
      registry.retry(retry -> {
        retry.getAttempts().convention(0);
      });
      TaskProvider<DockerTag> dockerTag = project.getTasks().named(registry.getTagTaskName(), DockerTag.class);

      project.getTasks().register("dockerPushTo" + capitalize(registry.getName()), DockerPush.class, push -> {
        push.setDescription("Push tagged Docker images to " + registry.getName());

        push.dependsOn(dockerTag);
        push.getTags().set(dockerTag.flatMap(DockerTag::getTags));

        push.getRegistry().set(registry);
      });
    });
    registries.registerBinding(MirantisRegistry.class, MirantisRegistry.class);
    registries.withType(MirantisRegistry.class).all(registry -> {
      project.getTasks().register(registry.getReadmePushTaskName(), MirantisPushReadme.class, readmePush -> {
        readmePush.setDescription("Push new Docker repository description to " + registry.getName());

        readmePush.getRegistry().set(registry);
        readmePush.getRepositoryName().set(buildExtension.getImageName());
        readmePush.getReadmeFile().fileProvider(dockerProcessReadme.map(s -> buildExtension.getDockerReadme().map(f -> new File(s.getDestinationDir(), f.getAsFile().getName())).getOrNull()));
      });
    });

    project.getTasks().register("dockerTagLocal", DockerTag.class, tag -> {
      tag.setDescription("Tag Docker images for local consumption.");

      tag.getTags().set(buildExtension.getImageName().zip(buildExtension.getTags(), (prefix, tags) -> tags.stream().map(t -> prefix + ":" + t).collect(toList())).orElse(emptyList()));
      tag.getImageId().set(dockerBuild.flatMap(DockerBuild::getImageId));
    });

    TaskProvider<DockerRmi> dockerClean = project.getTasks().register("dockerClean", DockerRmi.class, clean -> {
      clean.setDescription("Remove dangling built Docker images from the local docker instance");
      clean.getMetadata().set(buildExtension.getMetadata());
      clean.getFilters().add("dangling=true");
      clean.onlyIf(clean.dockerAvailable());
    });

    project.getTasks().named("clean", task -> task.dependsOn(dockerClean));

    project.getTasks().register("dockerPurge", DockerRmi.class, purge -> {
      purge.setDescription("Remove all built Docker images from the local docker instance");
      purge.getMetadata().set(buildExtension.getMetadata());
      purge.getArguments().add("--force");
    });

    project.getConfigurations().register("outgoingDocker", config -> {
      config.setDescription("Built Docker image-id files.");
      config.setCanBeConsumed(true);
      config.setCanBeResolved(false);
      config.setVisible(false);
      config.attributes(attrs -> attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, DOCKER_IMAGE_ID)));
      config.outgoing(outgoing -> outgoing.artifact(dockerBuild));
    });

    project.getPlugins().withType(CopyrightPlugin.class).configureEach(plugin ->
            ((ExtensionAware) buildExtension).getExtensions().add("copyright", plugin.createCopyrightSet(project, "docker", copyright -> {
              copyright.check(buildExtension.getDockerFile(), buildExtension.getDockerReadme(), buildExtension.getContentsDirectory());
            })));
  }

  private void configureBuildDefaults(Project project, DockerBuildExtension dockerBuild, BuildInfoExtension buildInfo) {
    dockerBuild.getRegistries().withType(DockerRegistry.class).configureEach(registry -> registry.getCredentials().convention(
            project.getProviders().credentials(PasswordCredentials.class, registry.getName() + "Docker")));

    Directory dockerSourceBase = project.getLayout().getProjectDirectory().dir("src/docker");
    dockerBuild.getDockerFile().convention(dockerSourceBase.file("Dockerfile"));
    dockerBuild.getBuildArgs().convention(emptyMap());
    dockerBuild.getDockerReadme().convention(dockerSourceBase.file("README.md"));
    dockerBuild.getContentsDirectory().convention(dockerSourceBase.dir("contents"));
    dockerBuild.getDocTemplates().convention(project.getLayout().getProjectDirectory().dir("../doc/templates"));
    dockerBuild.getDocMetadata().convention(emptyMap());
    dockerBuild.getSagDocDirectory().convention(project.getLayout().getProjectDirectory().dir("../doc/acr-data"));

    dockerBuild.getTags().convention(project.provider(() -> singletonList(project.getVersion().toString())));
    dockerBuild.getMetadata().put("gradle.build.host", getLocalHostName(project));
    dockerBuild.getMetadata().put("gradle.build.dir", project.getRootDir().getAbsolutePath());
    dockerBuild.getMetadata().put("gradle.build.project", project.getPath());

    dockerBuild.getMetadata().put("org.opencontainers.image.version", buildInfo.getVersion().map(Objects::toString));
    dockerBuild.getMetadata().put("org.opencontainers.image.created", buildInfo.getBuildTimestampISO8601());
    dockerBuild.getMetadata().put("org.opencontainers.image.revision", buildInfo.getRevision());
  }

  public Object limitedEval(Object version, DockerExtension extension, String expression) {
    Binding b = new Binding();
    b.setVariable("version", version);
    b.setVariable("docker", extension);
    GroovyShell sh = new GroovyShell(b);
    return sh.evaluate(expression);
  }
}
