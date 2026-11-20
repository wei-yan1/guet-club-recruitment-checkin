@echo off
setlocal enabledelayedexpansion
title student-login-crud

rem ==========================================================
rem  student-login-crud launcher (Windows)
rem  Usage: start.cmd [port]        default port: 8080
rem  Locates the JDK and Maven, compiles the current sources and
rem  starts the Spring Boot application in development mode.
rem ==========================================================

cd /d "%~dp0"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"

rem ---------- 1. locate the JDK ----------
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "C:\Users\MR\.jdks\openjdk-26.0.2\bin\java.exe" set "JAVA_EXE=C:\Users\MR\.jdks\openjdk-26.0.2\bin\java.exe"
if not defined JAVA_EXE if exist "D:\IntelliJ IDEA 2026.1.3\jbr\bin\java.exe" set "JAVA_EXE=D:\IntelliJ IDEA 2026.1.3\jbr\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
if not defined JAVA_EXE (
  echo [ERROR] java.exe was not found. Install JDK 17+ or set JAVA_HOME.
  pause
  exit /b 1
)
for %%H in ("%JAVA_EXE%\..\..") do set "JAVA_HOME=%%~fH"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [1/4] JDK    : %JAVA_EXE%

rem ---------- 2. locate Maven ----------
set "MVN_CMD="
for /f "delims=" %%M in ('where mvn 2^>nul') do if not defined MVN_CMD set "MVN_CMD=%%M"
if not defined MVN_CMD if exist "D:\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" set "MVN_CMD=D:\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
if not defined MVN_CMD if exist "%~dp0mvnw.cmd" set "MVN_CMD=%~dp0mvnw.cmd"
if not defined MVN_CMD (
  echo [ERROR] Maven was not found. Install Maven or keep mvnw.cmd in the project.
  pause
  exit /b 1
)
echo [2/4] Maven  : %MVN_CMD%

rem ---------- 3. make sure the port is free ----------
call :port_in_use %PORT%
if errorlevel 1 goto :port_ready
echo [WARN] Port %PORT% is already in use, usually the WSL dev stack.
set "FREE="
for /L %%P in (8081,1,8099) do (
  if not defined FREE (
    call :port_in_use %%P
    if errorlevel 1 set "FREE=%%P"
  )
)
if not defined FREE (
  echo [ERROR] No free port was found in 8081-8099.
  pause
  exit /b 1
)
set "PORT=!FREE!"
echo [WARN] Falling back to port %PORT%. Use "start.cmd 8080" once 8080 is free.
:port_ready
echo [3/4] Port   : %PORT%

sc query MySQL80 2>nul | find /i "RUNNING" >nul
if errorlevel 1 echo [WARN] MySQL80 is not running, the application may fail to connect to the database.

rem ---------- 4. compile and start ----------
echo [4/4] Compiling and starting, please wait...
echo        URL: http://localhost:%PORT%/
echo        Press Ctrl+C to stop the server.
echo.

call "%MVN_CMD%" -f "%~dp0pom.xml" -Dspring-boot.run.arguments=--server.port=%PORT% spring-boot:run

echo.
echo Server stopped with exit code %ERRORLEVEL%.
pause
exit /b 0

rem ---------- helpers ----------
:port_in_use
rem errorlevel 1 = free, errorlevel 0 = in use
netstat -ano | findstr /i "LISTENING" | findstr /c:":%~1 " >nul
if errorlevel 1 exit /b 1
exit /b 0
