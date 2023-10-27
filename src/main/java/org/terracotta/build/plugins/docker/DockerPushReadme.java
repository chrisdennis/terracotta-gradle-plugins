package org.terracotta.build.plugins.docker;

import org.apache.hc.client5.http.HttpResponseException;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.fluent.Executor;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.net.URI;

import static groovy.json.JsonOutput.toJson;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.Collections.singletonMap;
import static org.apache.hc.client5.http.fluent.Request.patch;

/**
 * Task that uploads readme content to a "Docker Trusted Registry" server.
 */
public abstract class DockerPushReadme extends DockerTask {

  public DockerPushReadme() {
    setGroup(PublishingPlugin.PUBLISH_TASK_GROUP);
    onlyIf(t -> getReadmeFile().map(f -> f.getAsFile().exists()).getOrElse(false));
  }

  @TaskAction
  public void pushReadme() throws Exception {
    Registry registry = getRegistry().get();

    URI pushUri = registry.getUri().get().resolve("api/v0/repositories/").resolve(registry.getOrganization().get() + "/").resolve(getRepositoryName().get());
    String body = toJson(singletonMap("longDescription", UTF_8.decode(wrap(readAllBytes(getReadmeFile().get().getAsFile().toPath())))));

    Request request = patch(pushUri).bodyString(body, ContentType.APPLICATION_JSON);

    if (registry.getCredentials().isPresent()) {
      PasswordCredentials credentials = registry.getCredentials().get();
      BasicScheme basicAuth = new BasicScheme();
      basicAuth.initPreemptive(new UsernamePasswordCredentials(credentials.getUsername(), credentials.getPassword().toCharArray()));
      request.addHeader(HttpHeaders.AUTHORIZATION, basicAuth.generateAuthResponse(null, null, null));
    }

    Executor executor = Executor.newInstance();

    withRetry("Push of " + getProject().getName() + " readme to " + pushUri, registry.getRetry(), IOException.class,
            () -> executor.execute(request).handleResponse(response -> {
              if (response.getCode() == HttpStatus.SC_OK) {
                return EntityUtils.toString(response.getEntity(), 1000);
              } else {
                throw new HttpResponseException(response.getCode(), response.getReasonPhrase());
              }
            }));
  }

  /**
   * Target registry configuration.
   *
   * @return registry configuration
   */
  @Input
  public abstract Property<Registry> getRegistry();

  /**
   * Target repository name.
   *
   * @return repository name
   */
  @Input
  public abstract Property<String> getRepositoryName();

  /**
   * Input file containing readme content.
   *
   * @return readme file
   */
  @InputFile
  public abstract RegularFileProperty getReadmeFile();
}
