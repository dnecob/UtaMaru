@echo off
setlocal
set "APP_HOME=%~dp0"
set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  echo ERROR: gradle/wrapper/gradle-wrapper.jar is missing.
  exit /b 1
)
"%JAVA_EXE%" "-Dorg.gradle.appname=gradlew" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
