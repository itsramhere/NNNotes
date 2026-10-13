@echo off
setlocal
set APP_HOME=%~dp0
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
endlocal & exit /b %ERRORLEVEL%
