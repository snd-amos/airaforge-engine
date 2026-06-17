@REM Tiny shim that forwards `mvn` to the Maven distribution bundled with
@REM IntelliJ IDEA, so commands like `mvn -q compile` work in any shell at
@REM this project's root without modifying the user's PATH.
@REM
@REM JAVA_HOME is set to the IntelliJ-bundled Temurin 21 JDK so Maven can
@REM find a compiler. If you move/upgrade IntelliJ or pick a different JDK,
@REM update both paths below.
@echo off
set "JAVA_HOME=C:\Users\soundye\.jdks\temurin-21.0.10"
"C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd" %*
