@echo off
rem HMCLNoIPCheck - start HMCL with the javaagent attached.
rem Put this file next to HMCLNoIPCheck.jar (and ideally next to the HMCL exe).
rem You can also drag an HMCL*.exe onto this file.

if not exist "%~dp0HMCLNoIPCheck.jar" (
  echo [HMCLNoIPCheck] Cannot find HMCLNoIPCheck.jar in "%~dp0"
  pause
  exit /b 1
)

set "TARGET=%~1"
if not defined TARGET (
  for %%F in ("%~dp0HMCL*.exe") do set "TARGET=%%~fF"
)

if not defined TARGET (
  echo [HMCLNoIPCheck] No HMCL*.exe found in "%~dp0"
  echo Put this file next to the HMCL exe, or drag the exe onto this file.
  pause
  exit /b 1
)

set HMCL_JAVA_OPTS=-javaagent:"%~dp0HMCLNoIPCheck.jar" %HMCL_JAVA_OPTS%
echo [HMCLNoIPCheck] Starting "%TARGET%"
echo [HMCLNoIPCheck] HMCL_JAVA_OPTS=%HMCL_JAVA_OPTS%
start "" "%TARGET%"
