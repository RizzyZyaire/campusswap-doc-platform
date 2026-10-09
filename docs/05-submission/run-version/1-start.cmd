@echo off
rem ===========================================================================
rem  CampusSwap -- one-click demo launcher (zero install)
rem
rem  Starts, all from THIS folder (no install, no config):
rem    1. mysql\bin\mysqld.exe    on port 3307  (portable MySQL, already seeded
rem                               with campusswap_db = 45 documents + 3 accounts)
rem    2. redis\redis-server.exe  on port 6380  (portable Redis, no persistence)
rem    3. app\campusswap-backend-1.0.0.jar via runtime\bin\java.exe on 10090
rem       -- the jar bundles the built frontend, so http://localhost:10090 IS
rem          the website (no Node, no pnpm, no Vite)
rem
rem  Ports are deliberately non-default (3307 / 6380 / 10090) so this coexists
rem  with a MySQL on 3306, a Redis on 6379, or the author's own dev stack on
rem  10087 + 5173.
rem
rem  WHY EVERY COMMAND USES A RELATIVE PATH AND AN EXPLICIT /D WORKING DIRECTORY:
rem  mysqld parses its command line through the ANSI code page, so an absolute
rem  path containing Chinese characters is TRUNCATED at the first CJK byte
rem  ("Failed to set datadir to 'D:\DevEnv\dist\data\'" -- measured 2026-10-09).
rem  Windows 8.3 short names are unavailable when 8.3 generation is disabled, so
rem  instead every program is started with a RELATIVE path plus start's /D switch
rem  (which sets the child's working directory) and only ASCII arguments such as
rem  "data" / "redis.conf". That makes the package work from any folder name.
rem  MySQL failures are written to mysql\launcher.err instead of --console so a
rem  error is readable after the window is gone.
rem  Pure ASCII: cmd.exe reads .cmd in the OEM code page, CJK bytes here would
rem  break its parser (learned the hard way twice).
rem ===========================================================================
setlocal enableextensions
cd /d "%~dp0"
chcp 65001 >nul 2>&1

set "BASE=%~dp0"
set "MYSQLPORT=3307"
set "REDISPORT=6380"
set "APPPORT=10090"
if not exist "%BASE%logs" mkdir "%BASE%logs" >nul 2>&1

title CampusSwap demo launcher
echo ============================================================
echo   CampusSwap  demo launcher  (zero install)
echo ============================================================
echo.

if not exist "%BASE%app\campusswap-backend-1.0.0.jar" goto no_jar
if not exist "%BASE%runtime\bin\java.exe" goto no_runtime
if not exist "%BASE%mysql\bin\mysqld.exe" goto no_mysql
if not exist "%BASE%mysql\data\campusswap_db" goto no_data

echo [1/4] portable MySQL on port %MYSQLPORT% ...
netstat -ano | find ":%MYSQLPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto mysql_up
if exist "%BASE%mysql\launcher.err" del "%BASE%mysql\launcher.err" >nul 2>&1
rem cd first so the relative "bin\mysqld.exe" resolves, AND pass /D so the child's
rem working directory is the mysql folder even if cmd's idea of the cwd differs.
cd /d "%BASE%mysql"
start "CampusSwap MySQL %MYSQLPORT%" /min /D "%BASE%mysql" bin\mysqld.exe --datadir=data --port=%MYSQLPORT% --mysqlx=0 --innodb-redo-log-capacity=33554432 --log-error=launcher.err
cd /d "%~dp0"
set /a T=0
:mysql_wait
netstat -ano | find ":%MYSQLPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto mysql_up
set /a T+=1
if %T% GEQ 40 goto mysql_dead
ping -n 2 127.0.0.1 >nul
goto mysql_wait
:mysql_up
echo       listening on %MYSQLPORT%
goto redis_step
:mysql_dead
echo       MySQL did not come up within 40s - its own log says:
echo       ------------------------------------------------------
if exist "%BASE%mysql\launcher.err" type "%BASE%mysql\launcher.err"
echo       ------------------------------------------------------
echo       (full log: mysql\launcher.err)
goto fail

:redis_step
echo.
echo [2/4] portable Redis on port %REDISPORT% ...
netstat -ano | find ":%REDISPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto redis_up
start "CampusSwap Redis %REDISPORT%" /min /D "%BASE%redis" redis-server.exe redis.conf
set /a T=0
:redis_wait
netstat -ano | find ":%REDISPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto redis_up
set /a T+=1
if %T% GEQ 30 goto redis_dead
ping -n 2 127.0.0.1 >nul
goto redis_wait
:redis_up
echo       listening on %REDISPORT%
goto app_step
:redis_dead
echo       Redis did not come up within 30s - see the minimized Redis window
goto fail

:app_step
echo.
echo [3/4] website (Spring Boot + bundled frontend) on port %APPPORT% ...
netstat -ano | find ":%APPPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto app_up
echo       starting the app - first boot takes 10-20 seconds
start "CampusSwap website (close this window to stop)" /D "%BASE%" runtime\bin\java.exe -Xmx512m -Dfile.encoding=UTF-8 -jar app\campusswap-backend-1.0.0.jar --spring.profiles.active=demo --server.port=%APPPORT% --logging.file.name=logs\demo-app.log
set /a T=0
:app_wait
netstat -ano | find ":%APPPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto app_up
set /a T+=1
if %T% GEQ 90 goto app_dead
ping -n 2 127.0.0.1 >nul
goto app_wait
:app_up
echo       listening on %APPPORT%
goto open_it
:app_dead
echo       the app did not come up within 180s
echo       last lines of logs\demo-app.log:
echo       ------------------------------------------------------
if exist "%BASE%logs\demo-app.log" powershell -NoProfile -Command "Get-Content -LiteralPath '%BASE%logs\demo-app.log' -Tail 15"
echo       ------------------------------------------------------
goto fail

:open_it
echo.
echo [4/4] opening the website ...
start "" http://localhost:%APPPORT%
echo.
echo ============================================================
echo   READY  -  website : http://localhost:%APPPORT%
echo             accounts: admin/Admin@123     (SYS_ADMIN, everything)
echo                       docadmin/Doc@123456  (DOC_ADMIN, review)
echo                       staff/Staff@123      (STAFF, author)
echo.
echo   To stop everything run 2-stop.cmd (or close the two windows).
echo   Logs: logs\demo-app.log  and  mysql\launcher.err
echo ============================================================
echo.
pause
exit /b 0

:no_jar
echo [ERROR] app\campusswap-backend-1.0.0.jar is missing.
echo         Unzip the WHOLE archive first (running it from inside the zip
echo         viewer extracts only the file you double-clicked).
goto fail

:no_runtime
echo [ERROR] runtime\bin\java.exe is missing - the bundled Java runtime is incomplete.
echo         Unzip the WHOLE archive again.
goto fail

:no_mysql
echo [ERROR] mysql\bin\mysqld.exe is missing - the bundled database is incomplete.
echo         Unzip the WHOLE archive again.
goto fail

:no_data
echo [ERROR] mysql\data\campusswap_db is missing - the bundled database has no data.
echo         Unzip the WHOLE archive again.
goto fail

:fail
echo.
echo Startup failed - nothing else was started.
pause
exit /b 1
