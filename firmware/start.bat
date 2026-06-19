@echo off
title Glove Game Station — Bridge Server
echo ============================================
echo   Glove Game Station
echo ============================================
echo.

REM Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found. Install Python 3.10+ from python.org
    pause & exit /b 1
)

REM Install / update dependencies silently
echo Installing dependencies...
pip install -q -r web\requirements.txt
if errorlevel 1 (
    echo ERROR: pip install failed.
    pause & exit /b 1
)

echo.
echo Starting bridge server...
echo Open your browser at: http://localhost:8766
echo.
python web\bridge.py

pause
