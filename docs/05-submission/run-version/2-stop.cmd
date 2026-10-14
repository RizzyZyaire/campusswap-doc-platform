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

echo [1/4] MySQL on 3307 ...
if not exist "%BASE%mysql\bin\mysqladmin.exe" goto mysql_skip
cd /d "%BASE%mysql"
bin\mysqladmin.exe --protocol=TCP --host=127.0.0.1 --port=3307 --user=root shutdown >nul 2>&1
if not errorlevel 1 goto mysql_done
rem one retry: a just-started mysqld may still be opening its sockets
ping -n 4 127.0.0.1 >nul
bin\mysqladmin.exe --protocol=TCP --host=127.0.0.1 --port=3307 --user=root shutdown >nul 2>&1
if errorlevel 1 echo       not running, or already shut down
if not errorlevel 1 echo       shut down cleanly
goto redis_stop
:mysql_done
echo       shut down cleanly
goto redis_stop
:mysql_skip
echo       mysqladmin not found - skipping the clean shutdown

:redis_stop
cd /d "%~dp0"
echo.
echo [2/4] Redis on 6380 ...
set "DONE=0"
for /f "tokens=5" %%p in ('netstat -ano ^| find ":6380" ^| find "LISTENING"') do (
  taskkill /PID %%p /T /F >nul 2>&1
  set "DONE=1"
)
if "%DONE%"=="0" echo       not running
if "%DONE%"=="1" echo       stopped

echo.
echo [3/4] website on 10090 ...
set "DONE=0"
for /f "tokens=5" %%p in ('netstat -ano ^| find ":10090" ^| find "LISTENING"') do (
  taskkill /PID %%p /T /F >nul 2>&1
  set "DONE=1"
)
if "%DONE%"=="0" echo       not running
if "%DONE%"=="1" echo       stopped

echo.
echo [4/4] Elasticsearch 9200 (only if this folder started it) ...
rem Stop ES only when the process owning 9200 really is an ES java process, so an
rem unrelated program that happens to own the port is never touched. ES is not
rem bundled with this package - this step only covers a local ES install that the
rem launcher picked up from ES_HOME or a known folder.
set "ESKILLED=0"
for /f "tokens=5" %%p in ('netstat -ano ^| find ":9200" ^| find "LISTENING"') do (
  for /f "delims=" %%c in ('powershell -NoProfile -Command "(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -eq %%p }).ExecutablePath"') do (
    echo %%c| findstr /i elasticsearch >nul
    if not errorlevel 1 (
      echo       stopping Elasticsearch PID %%p
      taskkill /PID %%p /T /F >nul 2>&1
      set "ESKILLED=1"
    )
  )
)
if "%ESKILLED%"=="1" echo       Elasticsearch stopped
if "%ESKILLED%"=="0" echo       not running (or not an Elasticsearch process)

echo.
echo All demo processes are stopped. The data stays in mysql\data, so the next
echo start shows exactly the same 45 documents and 3 accounts.
echo.
pause
exit /b 0
