@echo off
setlocal DisableDelayedExpansion
if not "%~3"=="" goto usage
if /i not "%~1"=="install" if /i not "%~1"=="uninstall" goto usage
if not "%~2"=="" if /i not "%~2"=="--preflight" goto usage
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" goto unsupported
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" goto unsupported

set "WANGSHU_ARCH=x86"
set "WANGSHU_REGSVR32=%SystemRoot%\System32\regsvr32.exe"
if /i "%PROCESSOR_ARCHITECTURE%"=="AMD64" goto windows64
if /i "%PROCESSOR_ARCHITEW6432%"=="AMD64" goto windows64
if /i not "%PROCESSOR_ARCHITECTURE%"=="x86" goto unsupported
if not "%PROCESSOR_ARCHITEW6432%"=="" goto unsupported
goto check32

:windows64
set "WANGSHU_ARCH=x64+x86"
set "WANGSHU_REGSVR32=%SystemRoot%\SysWOW64\regsvr32.exe"
set "WANGSHU_REGSVR64=%SystemRoot%\System32\regsvr32.exe"
if /i "%PROCESSOR_ARCHITECTURE%"=="x86" set "WANGSHU_REGSVR64=%SystemRoot%\Sysnative\regsvr32.exe"
set "WANGSHU_REQUIRED=%WANGSHU_REGSVR64%"
call :requireFile
if errorlevel 1 goto preflightFailed
set "WANGSHU_REQUIRED=%~dp0wangshu-ime-x64.dll"
call :requireFile
if errorlevel 1 goto preflightFailed

:check32
set "WANGSHU_REQUIRED=%WANGSHU_REGSVR32%"
call :requireFile
if errorlevel 1 goto preflightFailed
set "WANGSHU_REQUIRED=%~dp0wangshu-ime-x86.dll"
call :requireFile
if errorlevel 1 goto preflightFailed
if /i "%~2"=="--preflight" goto checked

set "WANGSHU_FLAG="
if /i "%~1"=="uninstall" set "WANGSHU_FLAG=/u"
if "%WANGSHU_ARCH%"=="x86" goto run32
"%WANGSHU_REGSVR64%" %WANGSHU_FLAG% "%~dp0wangshu-ime-x64.dll"
if errorlevel 1 goto failed64

:run32
"%WANGSHU_REGSVR32%" %WANGSHU_FLAG% "%~dp0wangshu-ime-x86.dll"
if errorlevel 1 goto failed32
exit /b 0

:requireFile
if not exist "%WANGSHU_REQUIRED%" goto missing
if exist "%WANGSHU_REQUIRED%\" goto missing
for %%F in ("%WANGSHU_REQUIRED%") do if "%%~zF"=="0" goto missing
exit /b 0
:missing
echo Required file missing or empty: "%WANGSHU_REQUIRED%"
exit /b 1

:checked
echo Windows registration file preflight passed for %WANGSHU_ARCH%.
echo Identity and signing checks are separate. Registration was not run.
exit /b 0

:preflightFailed
echo Registration preflight failed before any registrar was run.
exit /b 2

:failed64
echo The x64 registration operation failed. The x86 operation was not started.
goto failed

:failed32
echo The x86 registration operation failed. Preserve the result of both steps.
goto failed

:failed
echo Preserve the error and partial state for recovery.
exit /b 1

:unsupported
echo Supported Windows hosts are x86 and x64.
exit /b 2

:usage
echo Usage: registration.cmd install^|uninstall [--preflight]
exit /b 2
