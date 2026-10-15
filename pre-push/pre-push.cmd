@echo off
setlocal EnableExtensions
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js is missing.
    echo NOT SAFE TO COMMIT
    exit /b 1
)
node "%~dp0menu.mjs" %*
exit /b %errorlevel%
