@echo off
REM Glove Game Station — Windows compile + run script

echo == Glove Game Station ==
echo.

java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found. Install JDK 17+
    pause & exit /b 1
)

if not exist "lib\jSerialComm-2.10.4.jar" (
    echo ERROR: lib\jSerialComm-2.10.4.jar not found.
    echo Download from: https://github.com/Fazecast/jSerialComm/releases
    pause & exit /b 1
)

echo Compiling...
if not exist out mkdir out

REM Step 1: Compile Tetris FIRST (com.zetcode package)
REM so GameLauncher can reference com.zetcode.Tetris
javac -cp ".;lib\jSerialComm-2.10.4.jar" ^
    src\tetris\SerialManager.java ^
    src\tetris\Shape.java ^
    src\tetris\Board.java ^
    src\tetris\Tutorial.java ^
    src\tetris\Tetris.java ^
    -d out
if errorlevel 1 ( echo COMPILE FAILED - tetris & pause & exit /b 1 )

REM Step 2: Compile controller + flappy + launcher (default package)
REM Uses out/ on classpath so GameLauncher can see com.zetcode.Tetris
javac -cp ".;lib\jSerialComm-2.10.4.jar;out" ^
    src\controller\SerialManager.java ^
    src\controller\GloveController.java ^
    src\flappybird\App.java ^
    src\flappybird\FlappyBird.java ^
    src\launcher\GameLauncher.java ^
    -d out
if errorlevel 1 ( echo COMPILE FAILED - flappy/launcher & pause & exit /b 1 )

echo.
echo Compile OK. Launching...
java -cp "out;lib\jSerialComm-2.10.4.jar;assets\flappybird" GameLauncher

pause
