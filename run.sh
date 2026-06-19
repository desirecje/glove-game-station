#!/usr/bin/env bash
# Glove Game Station — Mac/Linux compile + run script

set -e

echo "== Glove Game Station =="
echo

# Check Java
if ! command -v javac &>/dev/null; then
    echo "ERROR: Java not found. Install JDK 17+ and add to PATH."
    exit 1
fi

# Check jSerialComm jar
JAR="lib/jSerialComm-2.11.0.jar"
if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR not found."
    echo "Download from: https://github.com/Fazecast/jSerialComm/releases"
    exit 1
fi

# Compile
echo "Compiling..."
mkdir -p out
javac -cp ".:$JAR" \
    src/controller/SerialManager.java \
    src/controller/GloveController.java \
    src/flappybird/FlappyBird.java \
    src/tetris/Shape.java \
    src/tetris/Board.java \
    src/tetris/Tutorial.java \
    src/tetris/Tetris.java \
    src/launcher/GameLauncher.java \
    -d out

echo "Compile OK. Launching..."
echo

# Run
java -cp "out:$JAR:assets/flappybird:assets/tetris:assets/racing" GameLauncher
