@echo off
setlocal DisableDelayedExpansion
if not "%~2"=="" goto usage
if "%~1"=="" goto normal
if /i not "%~1"=="--preflight" goto usage
"%~dp0registration.cmd" uninstall --preflight
:normal
"%~dp0registration.cmd" uninstall
:usage
echo Usage: uninstall.cmd [--preflight]
exit /b 2
