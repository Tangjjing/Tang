@echo off
chcp 65001 >nul
REM ============================================================
REM  DeepSeek Chat - one-click build for THIS machine
REM  Handles JDK 17 + the AF_UNIX NIO-selector workaround.
REM  Usage:  build.bat            (builds debug APK)
REM          build.bat clean      (passes args through to gradlew)
REM ============================================================

set "JAVA_HOME=C:\Users\Administrator\jdk-17.0.13+11"
set "JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Users/Administrator/.gradle/afunix"

REM The AF_UNIX self-pipe temp dir must exist (default Windows TEMP breaks it here).
if not exist "C:\Users\Administrator\.gradle\afunix" mkdir "C:\Users\Administrator\.gradle\afunix"

if "%~1"=="" (
    call gradlew.bat assembleDebug --console=plain
) else (
    call gradlew.bat %* --console=plain
)

echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo [OK] APK: app\build\outputs\apk\debug\app-debug.apk
) else (
    echo [!!] Build did not produce an APK. Check the output above.
)
