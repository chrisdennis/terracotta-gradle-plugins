package org.terracotta.build;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.RelativePath;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.internal.ExecException;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.terracotta.build.OutputUtils.logTo;
import static org.terracotta.build.OutputUtils.tee;

/**
 * Collection of utility methods used by both the plugins and the wider build.
 */
public class Utils {

  public static Action<FileCopyDetails> dropTopLevelDirectories(int count) {
    return fcd -> {
      RelativePath original = fcd.getRelativeSourcePath();
      String[] originalSegments = original.getSegments();
      if (count >= originalSegments.length) {
        if (original.isFile()) {
          throw new IllegalStateException("Cannot drop " + count + " " + (count > 1 ? "directories" : "directory") + " from " + original);
        } else {
          fcd.exclude();
        }
      } else {
        String[] targetSegments = fcd.getRelativePath().getSegments();
        String[] result = Arrays.copyOf(targetSegments, targetSegments.length - count);
        System.arraycopy(originalSegments, count, result, result.length - (originalSegments.length - count), originalSegments.length - count);
        fcd.setRelativePath(new RelativePath(original.isFile(), result));
      }
    };
  }

  private static final JavaLanguageVersion JAVA_8 = JavaLanguageVersion.of(8);

  public static Object jaxbRuntime(Object target) {
    if (target instanceof JavaLanguageVersion) {
      if (JAVA_8.compareTo((JavaLanguageVersion) target) < 0) {
        return "org.glassfish.jaxb:jaxb-runtime";
      } else {
        return null;
      }
    } else if (target instanceof Provider<?>) {
      return ((Provider<?>) target).map(t -> {
        Object o = Utils.jaxbRuntime(t);
        if (o instanceof Provider<?>) {
          return ((Provider<?>) o).getOrNull();
        } else {
          return o;
        }
      });
    } else if (target instanceof Test) {
      return jaxbRuntime(((Test) target).getJavaLauncher());
    } else if (target instanceof JavaLauncher) {
      return jaxbRuntime(((JavaLauncher) target).getMetadata().getLanguageVersion());
    } else {
      throw new IllegalArgumentException(target.toString());
    }
  }

  public static Map<String, Object> mapOf(Object ... arguments) {
    return mapOf(String.class, Object.class, arguments);
  }

  public static <K, V> Map<K, V> mapOf(Class<K> key, Class<V> value, Object ... arguments) {
    if (arguments.length % 2 != 0) {
      throw new IllegalArgumentException("Invalid argument count: " + arguments.length);
    } else {
      HashMap<K, V> map = new HashMap<>();
      for (int i = 0; i < arguments.length; i += 2) {
        map.put(key.cast(arguments[i]), value.cast(arguments[i + 1]));
      }
      return map;
    }
  }

  public static Map<String, String> artifact(String group, String name) {
    return mapOf(String.class, String.class, "group", group, "name", name);
  }

  public static Map<String, String> group(String group) {
    return mapOf(String.class, String.class, "group", group);
  }

  public static Map<String, String> coordinate(String path) {
    return mapOf(String.class, String.class, "path", path);
  }

  public static Map<String, String> coordinate(String path, String configuration) {
    return mapOf(String.class, String.class, "path", path, "configuration", configuration);
  }

  public static String getLocalHostName(Project project) {
    OperatingSystem os = OperatingSystem.current();
    try {
      if (os.isWindows()) {
        return requireNonNull(System.getenv("COMPUTERNAME"));
      } else if (os.isUnix()) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream mergedBytes = new ByteArrayOutputStream();
        OutputStream standardOut = tee(logTo(project.getLogger(), LogLevel.DEBUG), outBytes, mergedBytes);
        OutputStream errorOut = tee(logTo(project.getLogger(), LogLevel.INFO), mergedBytes);
        try {
          project.exec(spec -> {
            spec.executable("hostname");
            spec.setStandardOutput(standardOut);
            spec.setErrorOutput(errorOut);
          }).assertNormalExitValue();
        } catch (ExecException e) {
          project.getLogger().error(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(mergedBytes.toByteArray())).toString());
          throw e;
        }
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(outBytes.toByteArray())).toString().trim();
      } else {
        throw new UnsupportedOperationException("Unsupported operating system: " + os);
      }
    } catch (Throwable t) {
      project.getLogger().error("Could not retrieve hostname by conventional means, resorting to InetAddress.getLocalHost().getHostName()", t);
      try {
        return InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException unused) {
        return "<unknown-host>";
      }
    }
  }
}
