@echo off
setlocal DisableDelayedExpansion
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" goto unsupported
if /i "%PROCESSOR_ARCHITEW6432%"=="ARM64" goto unsupported
set "WANGSHU_REGSVR64=%SystemRoot%\System32\regsvr32.exe"
if exist "%SystemRoot%\Sysnative\regsvr32.exe" set "WANGSHU_REGSVR64=%SystemRoot%\Sysnative\regsvr32.exe"
if exist "%SystemRoot%\SysWOW64\regsvr32.exe" goto windows64
"%SystemRoot%\System32\regsvr32.exe"  "%~dp0wangshu-ime-x86.dll"
if errorlevel 1 goto failed
exit /b 0
:windows64
"%WANGSHU_REGSVR64%"  "%~dp0wangshu-ime-x64.dll"
if errorlevel 1 goto failed
"%SystemRoot%\SysWOW64\regsvr32.exe"  "%~dp0wangshu-ime-x86.dll"
if errorlevel 1 goto failed
exit /b 0
:failed
echo Registration operation failed. Preserve the error and partial state. No automatic elevation.
exit /b 1
:unsupported
echo Native Windows ARM64 is not supported by this development package.
exit /b 1
