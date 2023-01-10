package org.terracotta.build;

import org.apache.tools.ant.util.LineOrientedOutputStream;
import org.apache.tools.ant.util.TeeOutputStream;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;

import java.io.OutputStream;
import java.util.Arrays;

public class OutputUtils {

  public static OutputStream tee(OutputStream one, OutputStream ... more) {
    return Arrays.stream(more).reduce(one, TeeOutputStream::new);
  }

  public static OutputStream logTo(Logger logger, LogLevel level) {
    return new LineOrientedOutputStream() {
      @Override
      protected void processLine(String line) {
        logger.log(level, line);
      }
    };
  }
}
