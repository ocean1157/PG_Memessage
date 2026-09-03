@echo off
setlocal
cd /d "%~dp0"
if exist PGBugMailTracker.jar (
  start "PG Bug Mail Tracker" javaw -jar PGBugMailTracker.jar
  exit /b 0
)
if not exist out mkdir out
javac -encoding UTF-8 -d out legacy-java\src\PgBugMailTracker.java
if errorlevel 1 (
  pause
  exit /b 1
)
start "PG Bug Mail Tracker" javaw -cp out PgBugMailTracker
endlocal
