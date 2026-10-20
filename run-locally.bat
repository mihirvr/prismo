@echo off
rem Builds and starts Prismo from this folder (Windows).
rem
rem   run-locally.bat                 uses your normal Prismo data (%LOCALAPPDATA%\Prismo)
rem   run-locally.bat --test          uses a separate test data folder (test-data\Prismo here);
rem                                   your normal library, settings and thumbnails are not touched
rem   run-locally.bat --test D:\x     uses D:\x\Prismo as the test data folder
rem
rem Prismo keeps its database, thumbnails and logs in %LOCALAPPDATA%\Prismo, so a test run only points
rem LOCALAPPDATA somewhere else for this one window. Delete the test folder to start fresh.
rem See docs\RUN_LOCALLY.md for what to try by hand.

setlocal
cd /d "%~dp0"

if /i "%~1"=="--test" goto test
if not "%~1"=="" goto usage
echo Starting Prismo with your normal data in "%LOCALAPPDATA%\Prismo"
goto run

:test
if "%~2"=="" (set "TEST_DATA=%~dp0test-data") else (set "TEST_DATA=%~f2")
if not exist "%TEST_DATA%" mkdir "%TEST_DATA%"
set "LOCALAPPDATA=%TEST_DATA%"
echo Starting Prismo with TEST data in "%TEST_DATA%\Prismo"
goto run

:usage
echo Usage: run-locally.bat [--test [folder]]
exit /b 2

:run
call "%~dp0gradlew.bat" run
exit /b %ERRORLEVEL%
