# Quick-compile script for the JE _Parkour V2 engine.
#
# Usage:
#   .\compile.ps1                       # compile every Java file under src/main/java
#   .\compile.ps1 GameObject.java       # compile just GameObject.java (and required deps if missing)
#   .\compile.ps1 Foo.java Bar.java     # compile both
#
# Uses the Temurin 21 JDK bundled with IntelliJ and the Maven dependency jars
# under %USERPROFILE%\.m2. Output goes to target\classes — same directory the
# IDE builds into, so a subsequent IntelliJ run picks up the change.
#
# Why this exists: hand-typing the classpath of 10+ LWJGL/ImGui jars for every
# javac call was costing both Claude and the human time on every iteration.

$ErrorActionPreference = "Stop"

$jdk  = "C:\Users\soundye\.jdks\temurin-21.0.10\bin\javac.exe"
$root = $PSScriptRoot
$m2   = "$HOME\.m2\repository"

if (-not (Test-Path $jdk)) {
    Write-Error "JDK not found at $jdk. Update `$jdk in compile.ps1."
}

$cp = @(
    "$root\target\classes",
    "$m2\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar",
    "$m2\org\lwjgl\lwjgl-assimp\3.3.3\lwjgl-assimp-3.3.3.jar",
    "$m2\org\lwjgl\lwjgl-opengl\3.3.3\lwjgl-opengl-3.3.3.jar",
    "$m2\org\lwjgl\lwjgl-glfw\3.3.3\lwjgl-glfw-3.3.3.jar",
    "$m2\org\lwjgl\lwjgl-stb\3.3.3\lwjgl-stb-3.3.3.jar",
    "$m2\org\lwjgl\lwjgl-vulkan\3.3.3\lwjgl-vulkan-3.3.3.jar",
    "$m2\org\lwjgl\lwjgl-shaderc\3.3.3\lwjgl-shaderc-3.3.3.jar",
    "$m2\io\github\spair\imgui-java-binding\1.89.0\imgui-java-binding-1.89.0.jar",
    "$m2\io\github\spair\imgui-java-lwjgl3\1.89.0\imgui-java-lwjgl3-1.89.0.jar",
    "$m2\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar",
    "$m2\org\joml\joml\1.10.5\joml-1.10.5.jar"
) -join ';'

# Resolve input filenames against src/main/java/com/myengine if not absolute paths.
$srcDir = "$root\src\main\java\com\myengine"
$files = @()
if ($args.Count -eq 0) {
    $files = Get-ChildItem -Path $srcDir -Filter *.java -Recurse | ForEach-Object { $_.FullName }
    Write-Host "Compiling all $($files.Count) sources..."
} else {
    foreach ($arg in $args) {
        if (Test-Path $arg) { $files += (Resolve-Path $arg).Path }
        elseif (Test-Path "$srcDir\$arg") { $files += "$srcDir\$arg" }
        else { Write-Error "Source not found: $arg" }
    }
}

& $jdk -cp $cp -d "$root\target\classes" @files
if ($LASTEXITCODE -ne 0) {
    Write-Host "javac exit $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "OK ($($files.Count) file$(if ($files.Count -ne 1) { 's' }))" -ForegroundColor Green
