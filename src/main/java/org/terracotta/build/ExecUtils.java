package org.terracotta.build;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
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
}
