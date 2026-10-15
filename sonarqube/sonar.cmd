@echo off
setlocal EnableExtensions
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js is missing.
    exit /b 1
)
node "%~dp0sonar.mjs" %*
exit /b %errorlevel%
