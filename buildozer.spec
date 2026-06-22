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
source.include_patterns = config/*,retalert/*,data/*
# Keep the build lean: scratch storage, tests, and tooling stay out of the APK.
source.exclude_dirs = tests,docs,.git,.github,.venv,stora,storb,.pytest_cache,scripts
source.exclude_patterns = stora/*,storb/*,*.spec

icon.filename = %(source.dir)s/data/icon.png
presplash.filename = %(source.dir)s/data/presplash.png

version = 0.1.0
# mapview pulls requests; p4a needs its pure-Python transitive deps listed too.
requirements = python3,kivy,kivy_garden.mapview,rns,lxmf,requests,urllib3,idna,charset-normalizer,certifi,plyer

orientation = portrait
fullscreen = 0

# Emergency app: needs network, location, on-device audio/photo capture, a
# foreground service to keep listening, wake/vibrate, notifications, and BLE.
android.permissions = INTERNET,ACCESS_NETWORK_STATE,ACCESS_WIFI_STATE,ACCESS_FINE_LOCATION,ACCESS_COARSE_LOCATION,RECORD_AUDIO,CAMERA,FOREGROUND_SERVICE,WAKE_LOCK,VIBRATE,POST_NOTIFICATIONS,BLUETOOTH,BLUETOOTH_CONNECT,BLUETOOTH_SCAN

android.api = 34
android.minapi = 24
android.archs = arm64-v8a
android.allow_backup = 1
# Produce a sideload-installable APK from `buildozer android release` (default
# is an .aab app bundle, which needs bundletool/Play to install).
android.release_artifact = apk

[buildozer]
log_level = 2
warn_on_root = 0
