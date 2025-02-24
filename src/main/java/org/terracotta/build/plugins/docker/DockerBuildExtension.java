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

import org.gradle.api.Action;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

public abstract class DockerBuildExtension {

  private final CopySpec contents;
  private final ExtensiblePolymorphicDomainObjectContainer<Registry> registries;


  public DockerBuildExtension(Project project) {
    this.contents = project.copySpec();
    this.registries = project.getObjects().polymorphicDomainObjectContainer(Registry.class);
  }

  public CopySpec getContents() {
    return contents;
  }

  public void contents(Action<CopySpec> action) {
    action.execute(getContents());
  }

  public ExtensiblePolymorphicDomainObjectContainer<Registry> getRegistries() {
    return registries;
  }

  public abstract RegularFileProperty getDockerFile();

  public abstract DirectoryProperty getContentsDirectory();

  public abstract Property<String> getImageName();

  public abstract ListProperty<String> getTags();

  public abstract MapProperty<String, String> getMetadata();

  public abstract MapProperty<String, String> getBuildArgs();

  public abstract RegularFileProperty getDockerReadme();

  public abstract DirectoryProperty getDocTemplates();
  public abstract MapProperty<String, Object> getDocMetadata();

  public abstract DirectoryProperty getSagDocDirectory();
}
