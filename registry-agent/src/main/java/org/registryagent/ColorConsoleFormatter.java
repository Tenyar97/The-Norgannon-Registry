package org.registryagent;

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class ColorConsoleFormatter extends Formatter {

    private static final String RED    = "[31m";
    private static final String YELLOW = "[33m";
    private static final String RESET  = "[0m";

    @Override
    public String format(LogRecord record) {
        String color = null;
        if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
            color = RED;
        } else if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
            color = YELLOW;
        }

        String level = record.getLevel().getLocalizedName();
        String logger = record.getLoggerName();
        if (logger != null && logger.contains(".")) {
            logger = logger.substring(logger.lastIndexOf('.') + 1);
        }

        String message = formatMessage(record);

        Throwable thrown = record.getThrown();
        String stack = "";
        if (thrown != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(thrown);
            for (StackTraceElement e : thrown.getStackTrace()) {
                sb.append("\n\tat ").append(e);
            }
            stack = "\n" + sb;
        }

        String line = String.format("%s [%s] %s%n", level, logger, message + stack);

        if (color != null) {
            return color + line + RESET;
        }
        return line;
    }
}
