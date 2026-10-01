#!/usr/bin/env bash
# Fetch the LXMF-kt composite-build clone RetAlert builds against, at a pinned
# commit, and patch it for this build. Run from the repo root (CI does the same).
#
# Pins: LXMF-kt main after v0.0.14 (Nil `fields` decode for Sideband/Python
# interop, delivery announce handler so stamps are sent, propagation-sync path
# requests). Keep RNS_KT in step with reticulumKt in android/gradle/libs.versions.toml.
set -euo pipefail
LXMF_COMMIT=d9ea399b085bbf7f03955ea53eac42e362c68c1c
RNS_KT=3a4a696b9a
DIR=lxmf-kt

if [ ! -d "$DIR/.git" ]; then
  git init -q "$DIR"
  git -C "$DIR" remote add origin https://github.com/torlando-tech/LXMF-kt
fi
git -C "$DIR" fetch -q --depth 1 origin "$LXMF_COMMIT"
git -C "$DIR" checkout -q --force FETCH_HEAD

# :lxmf-examples applies the Shadow plugin, which is incompatible with Gradle 9
# and fails composite-build configuration even though only :lxmf-core is used.
sed -i 's/^include(":lxmf-examples")$/if (System.getenv("INCLUDE_EXAMPLES") != null) { include(":lxmf-examples") }/' "$DIR/settings.gradle.kts"
# Compile lxmf-core against the same reticulum-kt commit the app ships.
sed -i "s/\(com.github.torlando-tech.reticulum-kt:rns-[a-z]*:\)v0\.0\.22/\1$RNS_KT/" "$DIR/lxmf-core/build.gradle.kts"
echo "lxmf-kt at $(git -C "$DIR" rev-parse --short HEAD), reticulum-kt $RNS_KT"
