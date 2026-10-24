@echo off
rem HMCLNoIPCheck - open the injection window with a Java 17+ runtime.
rem Double-clicking the jar only works when a Java 17+ is the default JVM, so this script probes
rem JAVA_HOME and the usual JDK locations first.

set "AGENT=%~dp0HMCLNoIPCheck.jar"
if not exist "%AGENT%" (
  echo [HMCLNoIPCheck] Cannot find HMCLNoIPCheck.jar in "%~dp0"
  pause
  exit /b 1
)

set "JAVAW="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  "%JAVA_HOME%\bin\java.exe" -jar "%AGENT%" --version >nul 2>&1
  if not errorlevel 1 set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
)

if not defined JAVAW for /d %%D in ("C:\Program Files\Java\jdk-*") do (
  if not defined JAVAW if exist "%%~fD\bin\java.exe" (
    "%%~fD\bin\java.exe" -jar "%AGENT%" --version >nul 2>&1
    if not errorlevel 1 set "JAVAW=%%~fD\bin\javaw.exe"
  )
)

if not defined JAVAW for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*" "C:\Program Files\Microsoft\jdk-*" "C:\Program Files\Zulu\zulu-*") do (
  if not defined JAVAW if exist "%%~fD\bin\java.exe" (
    "%%~fD\bin\java.exe" -jar "%AGENT%" --version >nul 2>&1
    if not errorlevel 1 set "JAVAW=%%~fD\bin\javaw.exe"
  )
)

if not defined JAVAW (
  echo [HMCLNoIPCheck] No Java 17+ found.
  echo Set JAVA_HOME to a JDK 17+ or install one, then run this script again.
  pause
  exit /b 1
)

start "" "%JAVAW%" -jar "%AGENT%"
