#!/usr/bin/env bash
# Source this to get the RetAlert Android toolchain on PATH.
#   source android/env.sh
# Then run: ./gradlew :app:assembleDebug
export JAVA_HOME="$HOME/.local/retalert-toolchain/jdk21"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
echo "JAVA_HOME=$JAVA_HOME  ANDROID_HOME=$ANDROID_HOME"