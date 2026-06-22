# Buildozer config for the RetAlert Android APK (Kivy + python-for-android).
# Presence of this file switches on the APK build in .github/workflows/android.yml.
#
# Requirements are intentionally minimal for a reliable first build: RNS ships
# pure-Python crypto fallbacks, so `cryptography` is omitted (add it + pyserial
# later for native crypto speed and USB-serial RNode/LoRa support).

[app]
title = RetAlert
package.name = retalert
package.domain = org.retalert

source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json
source.include_patterns = config/*,retalert/*
# Keep the build lean: scratch storage, tests, and tooling stay out of the APK.
source.exclude_dirs = tests,docs,.git,.github,.venv,stora,storb,.pytest_cache
source.exclude_patterns = stora/*,storb/*,*.spec

version = 0.1.0
requirements = python3,kivy,rns,lxmf

orientation = portrait
fullscreen = 0

# Emergency app: needs network, location, on-device audio/photo capture, a
# foreground service to keep listening, wake/vibrate, notifications, and BLE.
android.permissions = INTERNET,ACCESS_NETWORK_STATE,ACCESS_WIFI_STATE,ACCESS_FINE_LOCATION,ACCESS_COARSE_LOCATION,RECORD_AUDIO,CAMERA,FOREGROUND_SERVICE,WAKE_LOCK,VIBRATE,POST_NOTIFICATIONS,BLUETOOTH,BLUETOOTH_CONNECT,BLUETOOTH_SCAN

android.api = 34
android.minapi = 24
android.archs = arm64-v8a
android.allow_backup = 1

[buildozer]
log_level = 2
warn_on_root = 0
