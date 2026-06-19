#!/usr/bin/env bash
set -e

echo "============================================"
echo "  Glove Game Station"
echo "============================================"
echo

# Check Python
if ! command -v python3 &>/dev/null; then
    echo "ERROR: python3 not found. Install Python 3.10+ from python.org"
    exit 1
fi

# Install dependencies
echo "Installing dependencies..."
pip3 install -q -r web/requirements.txt

echo
echo "Starting bridge server..."
echo "Open your browser at: http://localhost:8766"
echo
python3 web/bridge.py
