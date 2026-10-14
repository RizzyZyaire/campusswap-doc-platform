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

echo [1/5] portable MySQL on port %MYSQLPORT% ...
echo       (the very first run after unzipping can take 1-2 minutes:
echo        Windows Defender scans 200+ MB of freshly extracted files)
netstat -ano | find ":%MYSQLPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto mysql_up
if exist "%BASE%mysql\launcher.err" del "%BASE%mysql\launcher.err" >nul 2>&1
rem cd first so the relative "bin\mysqld.exe" resolves, AND pass /D so the child's
rem working directory is the mysql folder even if cmd's idea of the cwd differs.
cd /d "%BASE%mysql"
start "CampusSwap MySQL %MYSQLPORT%" /min /D "%BASE%mysql" bin\mysqld.exe --datadir=data --port=%MYSQLPORT% --mysqlx=0 --skip-log-bin --innodb-redo-log-capacity=33554432 --log-error=launcher.err
cd /d "%~dp0"
set /a T=0
:mysql_wait
netstat -ano | find ":%MYSQLPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto mysql_up
set /a T+=1
if %T% GEQ 90 goto mysql_dead
set /a R=%T% %% 8
if %R%==0 echo       still waiting for MySQL (%T% x 2s) - first run is slow, keep this window open
ping -n 2 127.0.0.1 >nul
goto mysql_wait
:mysql_up
echo       listening on %MYSQLPORT%
goto redis_step
:mysql_dead
echo       MySQL did not come up within 180s - its own log says:
echo       ------------------------------------------------------
if exist "%BASE%mysql\launcher.err" type "%BASE%mysql\launcher.err"
echo       ------------------------------------------------------
echo       (full log: mysql\launcher.err)
echo       If the log mentions VCRUNTIME140.dll, install the Microsoft
echo       Visual C++ 2015-2022 Redistributable (x64) once, then retry.
goto fail

:redis_step
echo.
echo [2/5] portable Redis on port %REDISPORT% ...
netstat -ano | find ":%REDISPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto redis_up
start "CampusSwap Redis %REDISPORT%" /min /D "%BASE%redis" redis-server.exe redis.conf
set /a T=0
:redis_wait
netstat -ano | find ":%REDISPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto redis_up
set /a T+=1
if %T% GEQ 60 goto redis_dead
ping -n 2 127.0.0.1 >nul
goto redis_wait
:redis_up
echo       listening on %REDISPORT%
goto es_step
:redis_dead
echo       Redis did not come up within 120s - see the minimized Redis window
goto fail

:es_step
echo.
echo [3/5] Elasticsearch 9200 (optional - full-text search engine) ...
rem Elasticsearch is NOT bundled (about 950 MB unzipped). Two situations are fine:
rem   * ES already listening on 9200 -> just use it (whoever started it);
rem   * no ES anywhere              -> the app falls back to the MySQL engine,
rem     search keeps working and nothing else is affected.
rem So: check the port FIRST (never start a second node, never mislabel a running
rem one), and only then look for a local install to start.
set "ESMODE=MySQL (no local Elasticsearch - search falls back automatically)"
netstat -ano | find ":9200" | find "LISTENING" >nul
if not errorlevel 1 goto es_running
rem ES_HOME first: the courseware install guide tells students to set it
set "ESDIR="
if defined ES_HOME if exist "%ES_HOME%\bin\elasticsearch.bat" set "ESDIR=%ES_HOME%"
for /d %%d in ("%SystemDrive%\Environment\elasticsearch-*") do if not defined ESDIR if exist "%%~fd\bin\elasticsearch.bat" set "ESDIR=%%~fd"
for /d %%d in ("%SystemDrive%\elasticsearch-*") do if not defined ESDIR if exist "%%~fd\bin\elasticsearch.bat" set "ESDIR=%%~fd"
for /d %%d in ("%SystemDrive%\DevEnv\11_Elasticsearch\elasticsearch-*") do if not defined ESDIR if exist "%%~fd\bin\elasticsearch.bat" set "ESDIR=%%~fd"
for /d %%d in ("%~d0\Environment\elasticsearch-*") do if not defined ESDIR if exist "%%~fd\bin\elasticsearch.bat" set "ESDIR=%%~fd"
for /d %%d in ("%~d0\elasticsearch-*") do if not defined ESDIR if exist "%%~fd\bin\elasticsearch.bat" set "ESDIR=%%~fd"
for /d %%d in ("%~d0\DevEnv\11_Elasticsearch\elasticsearch-*") do if not defined ESDIR if exist "%%~fd\bin\elasticsearch.bat" set "ESDIR=%%~fd"
if not defined ESDIR if exist "%ProgramFiles%\Elasticsearch\bin\elasticsearch.bat" set "ESDIR=%ProgramFiles%\Elasticsearch"
if not defined ESDIR goto es_absent
echo       found a local install at %ESDIR%
echo       starting it (20-40 seconds)
set "ES_JAVA_HOME=%ESDIR%\jdk"
start "CampusSwap Elasticsearch 9200" /min /D "%ESDIR%" bin\elasticsearch.bat
set /a ETRIES=0
:es_wait
netstat -ano | find ":9200" | find "LISTENING" >nul
if not errorlevel 1 goto es_up
set /a ETRIES+=1
if %ETRIES% GEQ 60 goto es_timeout
ping -n 2 127.0.0.1 >nul
goto es_wait
:es_running
echo       port 9200 is already in use - assuming Elasticsearch, leaving it alone
echo       (the website verifies compatibility and falls back if it is not ES 9.x)
set "ESMODE=Elasticsearch (already running on 9200)"
goto app_step
:es_up
echo       Elasticsearch is up on 9200
set "ESMODE=Elasticsearch (IK analyzer)"
goto app_step
:es_timeout
echo       ES did not open 9200 within 120s - see %ESDIR%\logs
echo       search will use the MySQL engine instead
goto app_step
:es_absent
echo       no local Elasticsearch and nothing on 9200 - search will use MySQL
echo       (install ES per courseware 10.1 to switch the search engine to ES)
goto app_step

:app_step

echo.
echo [4/5] website (Spring Boot + bundled frontend) on port %APPPORT% ...
netstat -ano | find ":%APPPORT%" | find "LISTENING" >nul
if errorlevel 1 goto app_start
rem Port already taken. It is almost always a PREVIOUS launch of this demo that is
rem still running -- and that instance may be an older jar from another folder,
rem which is exactly how "I fixed it but the browser still shows the old bug"
rem happens. Report who owns the port, then restart it from THIS folder so the
rem version on screen is always the version in this folder.
echo       port %APPPORT% is already in use; identifying the owner ...
rem The helper is a separate .ps1 on purpose -- see its header for why an inline
rem powershell -Command with escaped quotes cannot be used inside a .cmd.
powershell -NoProfile -ExecutionPolicy Bypass -File "%BASE%stop-old-instance.ps1"
netstat -ano | find ":%APPPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto app_busy
:app_start
echo       starting the app - first boot takes 10-30 seconds
echo       build in use:
powershell -NoProfile -Command "$j = Join-Path '%BASE%' 'app\campusswap-backend-1.0.0.jar'; if (Test-Path -LiteralPath $j) { $i = Get-Item -LiteralPath $j; $h = (Get-FileHash -LiteralPath $j -Algorithm SHA256).Hash.Substring(0,12); Write-Host ('        ' + $i.LastWriteTime.ToString('yyyy-MM-dd HH:mm') + '  sha256:' + $h) }"
start "CampusSwap website (close this window to stop)" /D "%BASE%" runtime\bin\java.exe -Xmx512m -Dfile.encoding=UTF-8 -jar app\campusswap-backend-1.0.0.jar --spring.profiles.active=demo --server.port=%APPPORT% --logging.file.name=logs\demo-app.log
set /a T=0
:app_wait
netstat -ano | find ":%APPPORT%" | find "LISTENING" >nul
if not errorlevel 1 goto app_up
set /a T+=1
if %T% GEQ 150 goto app_dead
set /a R=%T% %% 10
if %R%==0 echo       still starting the website (%T% x 2s) - keep this window open
ping -n 2 127.0.0.1 >nul
goto app_wait
:app_up
echo       listening on %APPPORT%
rem The TCP port opens a second or two before Spring can actually serve the page.
rem Wait until GET / really answers 200, so "READY" means "you can open it now"
rem and the browser never lands on a connection error (first-run complaint 2026-10-10).
set /a W=0
:app_warm
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -Uri 'http://127.0.0.1:%APPPORT%/' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { exit 0 } } catch { }; exit 1"
if not errorlevel 1 goto app_ready
set /a W+=1
if %W% GEQ 30 goto app_ready
ping -n 2 127.0.0.1 >nul
goto app_warm
:app_ready
echo       page is answering
goto open_it
:app_dead
echo       the app did not come up within 300s
echo       last lines of logs\demo-app.log:
echo       ------------------------------------------------------
if exist "%BASE%logs\demo-app.log" powershell -NoProfile -Command "Get-Content -LiteralPath '%BASE%logs\demo-app.log' -Tail 15"
echo       ------------------------------------------------------
goto fail

:app_busy
echo       port %APPPORT% is STILL busy after the clean-up attempt.
echo       Something else on this machine owns it (not this demo). Free the port
echo       or change APPPORT at the top of this file, then run me again.
goto fail

:open_it
echo.
echo [5/5] opening the website ...
echo       (if the browser was already open on this site, press Ctrl+F5 once:
echo        page HTML is served no-store, but an old tab may still hold old JS)
rem ?v= is a CACHE BUSTER, not decoration: a browser that still holds the OLD
rem index.html (served before this fix, when no Cache-Control was sent) may reuse it
rem without ever asking the server -- and that old page carries the old JS, so the
rem user keeps seeing the old behaviour no matter what we fix server-side.
rem A different URL is a different cache key, so the fresh HTML is fetched for sure.
start "" "http://localhost:%APPPORT%/?v=%RANDOM%%RANDOM%"
echo.
echo ============================================================
echo   READY  -  website : http://localhost:%APPPORT%
echo             accounts: admin/Admin@123     (SYS_ADMIN, everything)
echo                       docadmin/Doc@123456  (DOC_ADMIN, review)
echo                       staff/Staff@123      (STAFF, author)
echo.
echo   full-text search engine: %ESMODE%
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
