package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.NamedDomainObjectSet;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.attributes.Category;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectComponentPublication;
import org.gradle.api.internal.component.SoftwareComponentInternal;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.MavenPomInternal;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.publish.maven.internal.publisher.MavenPublicationCoordinates;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.jvm.tasks.Jar;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.terracotta.build.PluginUtils.capitalize;

/**
 * Deploy plugin for published artifacts. This is an abstraction over the {@code maven-publish} plugin.
 * <p>
 * This sets up a bunch of default behaviors and provides a more direct configuration mechanism:
 * <pre>
 * deploy {
 *   groupId = 'com.terracottatech.internal'
 *   artifactId = 'security-common-test'
 *   name = 'Terracotta Security common test module'
 *   description = 'Contains code and files common for security testing'
 * }
 * </pre>
 * Defaults:
 * <ul>
 *   <li>POM: {@code <organization>} and {@code <developers>} section content</li>
 *   <li>POM copied to {@code META-INF/maven/groupId/artifactId/pom.xml}</li>
 *   <li>Javadoc and Source JAR Publishing</li>
 *   <li>{@code install} as alias of {@code publishToMavenLocal}</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public class DeployPlugin implements Plugin<Project> {

  public static final String METADATA_CATEGORY = "metadata";

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(MavenPublishPlugin.class);
    project.getExtensions().create(TcDeployExtension.class, "deploy", TcDeployExtension.class, project);

    project.getExtensions().configure(PublishingExtension.class, publishing -> publishing.getPublications().withType(MavenPublication.class).configureEach(mavenPublication -> {
      mavenPublication.pom(pom -> {
        pom.organization(org -> {
          org.getName().set("Super iPaaS Integration LLC, an IBM Company");
          org.getUrl().set("http://terracotta.org");
        });
        pom.developers(devs -> devs.developer(dev -> {
          dev.getName().set("Terracotta Engineers");
          dev.getEmail().set("dev-internal@terracottatech.com");
          dev.getOrganization().set("Super iPaaS Integration LLC, an IBM Company");
        }));
      });

      project.getTasks().register("generatePomPropertiesFor" + capitalize(mavenPublication.getName()) + "Publication", GenerateMavenProperties.class).configure(task -> {
        task.setDescription("Generates the Maven POM properties file for publication '" + mavenPublication.getName() + "'.");
        task.setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
        task.getProjectIdentity().value(((MavenPublicationInternal) mavenPublication).getCoordinates(ModuleVersionIdentifier.class));
        task.getDestination().value(project.getLayout().getBuildDirectory().file("publications/" + mavenPublication.getName() +"/pom.properties"));
      });
    }));

    /*
     * Do **not** convert the anonymous Action here to a lambda expression - it will break Gradle's up-to-date tracking
     * and cause tasks to be needlessly rerun.
     */
    //noinspection Convert2Lambda
    project.getTasks().withType(AbstractPublishToMaven.class).configureEach(publishTask -> publishTask.doFirst(new Action<Task>() {
      @Override
      public void execute(Task task) {
        MavenPublication publication = publishTask.getPublication();
        if (publication instanceof ProjectComponentPublication) {
          Provider<SoftwareComponentInternal> component = ((ProjectComponentPublication) publication).getComponent();
          if (component.isPresent()) { //The shadow plugin doesn't associate a component with the publication
            Collection<ProjectDependency> unpublishedDeps = component.get().getUsages().stream().flatMap(usage ->
                    usage.getDependencies().stream().filter(ProjectDependency.class::isInstance).map(ProjectDependency.class::cast).filter(moduleDependency ->
                            !moduleDependency.getDependencyProject().getPlugins().hasPlugin(DeployPlugin.class))).collect(Collectors.toList());
            if (!unpublishedDeps.isEmpty()) {
              project.getLogger().warn("{} has applied the deploy plugin but has unpublished project dependencies: {}", project, unpublishedDeps);
            }
          }
        }
      }
    }));

    ConfigurationContainerInternal configurations = (ConfigurationContainerInternal) project.getConfigurations();

    TaskProvider<Sync> register = project.getTasks().register("outgoingMetadataSync", Sync.class, sync -> {
      sync.into(project.getLayout().getBuildDirectory().dir("metadata"));
      project.getTasks().withType(GenerateMavenPom.class).forEach(pomTask -> sync.from(pomTask, s -> {
        MavenPublicationCoordinates projectIdentity = ((MavenPomInternal) pomTask.getPom()).getCoordinates();
        String pomFile = projectIdentity.getArtifactId().get() + "-" + projectIdentity.getVersion().get() + ".pom";
        s.rename(".*", pomFile);
      }));
      project.getTasks().withType(GenerateModuleMetadata.class).forEach(moduleTask -> sync.from(moduleTask, s -> {
        MavenPublication mavenPublication = (MavenPublication) moduleTask.getPublication().get();
        String moduleFile = mavenPublication.getArtifactId() + "-" + mavenPublication.getVersion() + ".module";
        s.rename(".*", moduleFile);
      }));
    });
    configurations.consumable("metadataElements",
            c -> c.attributes(attrs -> attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, METADATA_CATEGORY)))
                    .getOutgoing().artifact(register));


    project.getTasks().register("install", task ->
      task.dependsOn(project.getTasks().named(MavenPublishPlugin.PUBLISH_LOCAL_LIFECYCLE_TASK_NAME)
    ));

    project.getPlugins().withType(JavaPlugin.class).configureEach(plugin -> {

      project.getTasks().named(JavaPlugin.JAVADOC_TASK_NAME, Javadoc.class, javadoc -> {
        BasePluginExtension basePluginConvention = project.getExtensions().getByType(BasePluginExtension.class);
        javadoc.setTitle(basePluginConvention.getArchivesName().get() + " " + project.getVersion() + " API");
      });

      project.getTasks().withType(Javadoc.class).configureEach(javadoc -> {
        javadoc.exclude("**/internal/**");
        ((CoreJavadocOptions) javadoc.getOptions()).addBooleanOption("Xdoclint:none", true);
      });

      project.getExtensions().configure(JavaPluginExtension.class, java -> {
        java.withJavadocJar();
        java.withSourcesJar();
      });

      project.afterEvaluate(p -> {
        p.getExtensions().configure(PublishingExtension.class, publishing -> {
          if (publishing.getPublications().isEmpty()) {
            publishing.publications(publications -> publications.register("mavenJava", MavenPublication.class, mavenJava -> mavenJava.from(p.getComponents().getByName("java"))));
          }
        });
      });
    });

    project.afterEvaluate(p -> {
      TaskCollection<GenerateMavenPom> poms = p.getTasks().withType(GenerateMavenPom.class);

      p.getTasks().withType(Jar.class).configureEach(jar -> poms.forEach(pomTask -> {
        MavenPublicationCoordinates identity = ((MavenPomInternal) pomTask.getPom()).getCoordinates();
        jar.from(pomTask, spec -> {
          spec.into("META-INF/maven/" + identity.getGroupId().get() + "/" + identity.getArtifactId().get());
          spec.rename(".*", "pom.xml");
        });
      }));

      TaskCollection<GenerateMavenProperties> properties = p.getTasks().withType(GenerateMavenProperties.class);

      p.getTasks().withType(Jar.class).configureEach(jar -> properties.forEach(propertiesTask -> {
        Property<ModuleVersionIdentifier> identity = propertiesTask.getProjectIdentity();
        jar.from(propertiesTask, spec -> {
          spec.into(identity.map(id -> "META-INF/maven/" + id.getGroup() + "/" + id.getName()));
          spec.rename(".*", "pom.properties");
        });
      }));
    });
  }

  public static class TcDeployExtension {

    private final Project project;
    private final NamedDomainObjectSet<MavenPublication> mavenPublications;

    public TcDeployExtension(Project project) {
      this.project = project;
      this.mavenPublications = project.getExtensions().getByType(PublishingExtension.class).getPublications().withType(MavenPublication.class);
    }

    void setGroupId(String groupId) {
      mavenPublications.configureEach(mavenPublication -> mavenPublication.setGroupId(groupId));
    }

    void setArtifactId(String artifactId) {
      project.getExtensions().configure(BasePluginExtension.class, basePluginExtension -> {
        basePluginExtension.getArchivesName().value(artifactId);
      });
      mavenPublications.configureEach(mavenPublication -> mavenPublication.setArtifactId(artifactId));
    }

    void setName(String name) {
      mavenPublications.configureEach(mavenPublication -> mavenPublication.getPom().getName().set(name));
    }

    void setDescription(String description) {
      mavenPublications.configureEach(mavenPublication -> mavenPublication.getPom().getDescription().set(description));
    }
  }

  public static abstract class GenerateMavenProperties extends DefaultTask {

    @OutputFile
    public abstract RegularFileProperty getDestination();

    @Input
    public abstract Property<ModuleVersionIdentifier> getProjectIdentity();

    @TaskAction
    public void writeProperties() throws IOException {
      Properties mavenProperties = new Properties();
      mavenProperties.setProperty("groupId", getProjectIdentity().get().getGroup());
      mavenProperties.setProperty("artifactId", getProjectIdentity().get().getName());
      mavenProperties.setProperty("version", getProjectIdentity().get().getVersion());

      try (OutputStream output = Files.newOutputStream(getDestination().get().getAsFile().toPath())) {
        mavenProperties.store(output, "Created by Gradle " + getProject().getGradle().getGradleVersion());
      }
    }
  }
}
