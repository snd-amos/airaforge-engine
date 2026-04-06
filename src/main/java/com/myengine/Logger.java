package com.myengine;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralized logging system for the Game Engine.
 * Provides formatted console output with timestamps and severity levels.
 */
public class Logger {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public enum Level {
        INFO,
        WARN,
        ERROR,
        DEBUG
    }

    public static void info(String message) {
        log(Level.INFO, message);
    }

    public static void warn(String message) {
        log(Level.WARN, message);
    }

    public static void error(String message) {
        log(Level.ERROR, message);
    }

    public static void error(String message, Exception e) {
        log(Level.ERROR, message);
        e.printStackTrace();
    }

    public static void debug(String message) {
        log(Level.DEBUG, message);
    }

    private static void log(Level level, String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        System.out.printf("[%s] [%s] %s%n", timestamp, level, message);
    }
}