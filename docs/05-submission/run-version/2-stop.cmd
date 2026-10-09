@echo off
rem ===========================================================================
rem  CampusSwap -- stop the zero-install demo (clean shutdown)
rem    MySQL : mysqladmin shutdown  (flushes InnoDB so the data folder stays
rem            reusable; never taskkill it, that leaves a dirty datadir)
rem    Redis : taskkill by port     (no persistence configured, nothing to flush)
rem    App   : taskkill by port
rem  Paths stay relative for the same reason as in 1-start.cmd: mysqld and the
rem  mysql client parse their command line through the ANSI code page, so an
rem  absolute path containing Chinese characters would be truncated.
rem  Pure ASCII on purpose (cmd.exe reads .cmd in the OEM code page).
rem ===========================================================================
setlocal enableextensions
cd /d "%~dp0"
set "BASE=%~dp0"

echo ============================================================
echo   CampusSwap  demo stop
echo ============================================================
echo.

echo [1/3] MySQL on 3307 ...
if not exist "%BASE%mysql\bin\mysqladmin.exe" goto mysql_skip
cd /d "%BASE%mysql"
bin\mysqladmin.exe --protocol=TCP --host=127.0.0.1 --port=3307 --user=root shutdown >nul 2>&1
if errorlevel 1 echo       not running, or already shut down
if not errorlevel 1 echo       shut down cleanly
goto redis_stop
:mysql_skip
echo       mysqladmin not found - skipping the clean shutdown

:redis_stop
cd /d "%~dp0"
echo.
echo [2/3] Redis on 6380 ...
set "DONE=0"
for /f "tokens=5" %%p in ('netstat -ano ^| find ":6380" ^| find "LISTENING"') do (
  taskkill /PID %%p /T /F >nul 2>&1
  set "DONE=1"
)
if "%DONE%"=="0" echo       not running
if "%DONE%"=="1" echo       stopped

echo.
echo [3/3] website on 10090 ...
set "DONE=0"
for /f "tokens=5" %%p in ('netstat -ano ^| find ":10090" ^| find "LISTENING"') do (
  taskkill /PID %%p /T /F >nul 2>&1
  set "DONE=1"
)
if "%DONE%"=="0" echo       not running
if "%DONE%"=="1" echo       stopped

echo.
echo All demo processes are stopped. The data stays in mysql\data, so the next
echo start shows exactly the same 45 documents and 3 accounts.
echo.
pause
exit /b 0
