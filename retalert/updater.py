"""RetAlert self-updater.

Early-priority cross-cutting feature (see user requirement + PROMPT.md). Checks
the project's GitHub releases, defaults to the **latest release tag** (non-
prerelease), downloads, installs into the current Python environment, and
cleans up its own temp artifacts afterwards.

* Channel: latest release tag by default; ``--tag <tag>`` installs a specific
  release; ``--prerelease`` opts into the newest prerelease.
* Source: prefers a release asset (sdist/wheel) if present, else the GitHub
  source tarball.
* Install: ``python -m pip install`` the downloaded artifact into the running
  interpreter's environment.
* Cleanup: temp download dir is always removed (success or failure); old
  downloaded artifacts are not retained.

Android APK self-update is a later, separate path (build step 11+); this
module targets the Python (desktop/daemon) environment for now.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

from . import __version__

REPO = "idan2025/RetAlert"
GH_API_RELEASES_LATEST = f"https://api.github.com/repos/{REPO}/releases/latest"
GH_API_RELEASES = f"https://api.github.com/repos/{REPO}/releases"


@dataclass
class Release:
    tag: str
    prerelease: bool
    tarball_url: str
    asset_urls: List[str]

    @property
    def version(self) -> str:
        return self.tag.lstrip("v")


def _gh_available() -> bool:
    return shutil.which("gh") is not None


def _gh_api_latest() -> Release:
    try:
        out = subprocess.check_output(
            ["gh", "api", f"repos/{REPO}/releases/latest", "--jq",
             "{tag:.tag_name, prerelease:.prerelease, tarball:.tarball_url, "
             "assets:[.assets[].browser_download_url]}"],
            text=True,
        )
    except subprocess.CalledProcessError as exc:
        raise LookupError(
            f"could not fetch latest release for {REPO} via gh "
            f"(exit {exc.returncode}). Has a release been published? "
            f"Use `gh release create` or pass --tag."
        ) from exc
    data = json.loads(out)
    return Release(
        tag=data["tag"],
        prerelease=bool(data.get("prerelease")),
        tarball_url=data["tarball"],
        asset_urls=list(data.get("assets") or []),
    )


def _gh_api_all() -> List[Release]:
    try:
        out = subprocess.check_output(
            ["gh", "api", f"repos/{REPO}/releases", "--jq",
             "[.[]|{tag:.tag_name, prerelease:.prerelease, tarball:.tarball_url, "
             "assets:[.assets[].browser_download_url]}]"],
            text=True,
        )
    except subprocess.CalledProcessError as exc:
        raise LookupError(
            f"could not list releases for {REPO} via gh (exit {exc.returncode})."
        ) from exc
    data = json.loads(out)
    return [
        Release(
            tag=d["tag"], prerelease=bool(d.get("prerelease")),
            tarball_url=d["tarball"], asset_urls=list(d.get("assets") or []),
        )
        for d in data
    ]


def _urllib_get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json",
                                               "User-Agent": "retalert-updater"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            raise LookupError(
                f"no release found at {url} (404). Has a release been published?"
            ) from exc
        raise


def _urllib_latest() -> Release:
    d = _urllib_get_json(GH_API_RELEASES_LATEST)
    return Release(
        tag=d["tag_name"], prerelease=bool(d.get("prerelease")),
        tarball_url=d["tarball_url"],
        asset_urls=[a["browser_download_url"] for a in d.get("assets", [])],
    )


def _urllib_all() -> List[Release]:
    data = _urllib_get_json(GH_API_RELEASES)
    return [
        Release(
            tag=d["tag_name"], prerelease=bool(d.get("prerelease")),
            tarball_url=d["tarball_url"],
            asset_urls=[a["browser_download_url"] for a in d.get("assets", [])],
        )
        for d in data
    ]


def fetch_latest() -> Release:
    """Latest non-prerelease release (default channel)."""
    if _gh_available():
        return _gh_api_latest()
    return _urllib_latest()


def fetch_all() -> List[Release]:
    """All releases, newest first (for ``--prerelease`` / ``--tag`` lookup)."""
    if _gh_available():
        return _gh_api_all()
    return _urllib_all()


def fetch_tag(tag: str) -> Release:
    for r in fetch_all():
        if r.tag.lstrip("v") == tag.lstrip("v") or r.tag == tag:
            return r
    raise LookupError(f"release tag {tag!r} not found in {REPO}")


def is_newer(latest: str, current: str = __version__) -> bool:
    """True if ``latest`` version is greater than ``current`` (semver-ish)."""
    def parts(v: str):
        out = []
        for p in v.lstrip("v").split("."):
            num = ""
            for ch in p:
                if ch.isdigit():
                    num += ch
                else:
                    break
            out.append(int(num) if num else 0)
        return out
    return parts(latest) > parts(current)


def _pick_install_source(release: Release) -> str:
    """Prefer a packaged asset (sdist/wheel) over the source tarball."""
    for url in release.asset_urls:
        low = url.lower()
        if low.endswith((".whl", ".tar.gz", ".zip")):
            return url
    return release.tarball_url


def _download(url: str, dest_dir: Path) -> Path:
    """Download ``url`` into ``dest_dir``; return the local path."""
    name = url.rstrip("/").split("/")[-1] or "release-archive"
    dest = dest_dir / name
    # gh-hosted tarball_url redirects; urllib follows redirects by default.
    req = urllib.request.Request(url, headers={"User-Agent": "retalert-updater"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as fh:
        shutil.copyfileobj(resp, fh)
    return dest


def _pip_install(path: Path) -> None:
    subprocess.check_call(
        [sys.executable, "-m", "pip", "install", "--upgrade", "--no-input", str(path)],
    )


def check() -> Release:
    """Return the latest release (does not install)."""
    return fetch_latest()


def update(tag: Optional[str] = None, prerelease: bool = False,
           yes: bool = False) -> Release:
    """Check, download, install, and clean. Returns the installed Release.

    * ``tag``: install a specific release tag (skips latest-channel lookup).
    * ``prerelease``: consider prereleases when picking the newest.
    * ``yes``: skip the interactive confirm prompt.
    """
    if tag:
        release = fetch_tag(tag)
    elif prerelease:
        releases = fetch_all()
        if not releases:
            raise LookupError(f"no releases found in {REPO}")
        release = max(releases, key=lambda r: r.version)
    else:
        release = fetch_latest()

    if not tag and not prerelease and not is_newer(release.version, __version__):
        # Nothing to do, but still return the release for reporting.
        return release

    if not yes:
        print(f"RetAlert {__version__} -> {release.tag} ({REPO})")
        if input("Install? [y/N] ").strip().lower() not in ("y", "yes"):
            print("aborted")
            return release

    tmp = Path(tempfile.mkdtemp(prefix="retalert-update-"))
    try:
        source_url = _pick_install_source(release)
        artifact = _download(source_url, tmp)
        _pip_install(artifact)
        print(f"installed RetAlert {release.tag}")
    finally:
        # Clean after itself: always remove temp download dir.
        shutil.rmtree(tmp, ignore_errors=True)

    return release