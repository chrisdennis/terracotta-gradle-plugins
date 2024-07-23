/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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
