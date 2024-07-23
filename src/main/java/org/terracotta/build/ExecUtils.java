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

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecSpec;
import org.gradle.process.internal.ExecException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.terracotta.build.OutputUtils.logTo;
import static org.terracotta.build.OutputUtils.tee;

public class ExecUtils {
    private static final Logger LOGGER = Logging.getLogger(ExecUtils.class);

    public static String execUnder(Task task, Action<ExecSpec> action) {
        return execUnder(task, LogLevel.INFO, LogLevel.ERROR, action);
    }

    public static String execQuietlyUnder(Task task, Action<ExecSpec> action) {
        return execUnder(task, LogLevel.DEBUG, LogLevel.DEBUG, action);
    }

    public static String execUnder(Task task, LogLevel output, LogLevel failure, Action<ExecSpec> action) {
        Logger logger = task.getLogger();

        ByteArrayOutputStream mergedBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        try (OutputStream standardOut = tee(logTo(logger, output), outBytes, mergedBytes);
             OutputStream errorOut = tee(logTo(logger, output), mergedBytes)) {
            task.getProject().exec(spec -> {
                spec.setStandardOutput(standardOut);
                spec.setErrorOutput(errorOut);
                action.execute(spec);
            }).assertNormalExitValue();
        } catch (ExecException e) {
            if (!logger.isEnabled(output) && logger.isEnabled(failure)) {
                logger.log(failure, StandardCharsets.UTF_8.decode(ByteBuffer.wrap(mergedBytes.toByteArray())).toString());
            }
            throw e;
        } catch (IOException e) {
            throw new GradleException("Unexpected exception closing process output streams", e);
        }

        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(outBytes.toByteArray())).toString();
    }

    public static String execute(ExecOperations execOperations, Action<ExecSpec> action) throws ExecException {
        return execute(execOperations, LogLevel.INFO, LogLevel.ERROR, action);
    }

    public static String executeQuietly(ExecOperations execOperations, Action<ExecSpec> action) throws ExecException {
        return execute(execOperations, LogLevel.DEBUG, LogLevel.DEBUG, action);
    }

    public static String execute(ExecOperations execOperations, LogLevel output, LogLevel failure, Action<ExecSpec> action) throws ExecException {
        ByteArrayOutputStream mergedBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        try (OutputStream standardOut = tee(logTo(LOGGER, output), outBytes, mergedBytes);
             OutputStream errorOut = tee(logTo(LOGGER, output), mergedBytes)) {
            execOperations.exec(spec -> {
                spec.setStandardOutput(standardOut);
                spec.setErrorOutput(errorOut);
                action.execute(spec);
            }).assertNormalExitValue();
        } catch (ExecException e) {
            if (!LOGGER.isEnabled(output) && LOGGER.isEnabled(failure)) {
                LOGGER.log(failure, StandardCharsets.UTF_8.decode(ByteBuffer.wrap(mergedBytes.toByteArray())).toString());
            }
            throw e;
        } catch (IOException e) {
            throw new GradleException("Unexpected exception closing process output streams", e);
        }
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(outBytes.toByteArray())).toString();
    }
}
