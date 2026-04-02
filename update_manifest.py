#!/usr/bin/env python3
"""
update_manifest.py  —  run from the gh-pages branch root.
Inserts a new version entry into manifest.json, keeping the list
sorted newest-first so Jellyfin always picks the latest version.
"""

import argparse
import json
import os
import sys

PLUGIN_GUID        = "6c8a80b7-3e2f-4d5a-9b1c-f7e8d9a0b2c3"
REPO_OWNER         = "ciantm"
REPO_NAME          = "Musian"
PLUGIN_NAME        = "Musian"
TARGET_ABI         = "10.8.0.0"   # minimum Jellyfin version required
MANIFEST_FILE      = "manifest.json"

BASE_URL = f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases/download"


def load_manifest() -> list:
    if os.path.exists(MANIFEST_FILE):
        with open(MANIFEST_FILE, "r") as f:
            return json.load(f)
    # Bootstrap a fresh manifest
    return [
        {
            "category":    "Music",
            "guid":        PLUGIN_GUID,
            "name":        PLUGIN_NAME,
            "description": "Play music from your Jellyfin library by selecting a mood on a visual emotion wheel (Valence–Arousal model).",
            "overview":    "Click anywhere on the colour wheel to match music to how you feel. The wheel maps High/Low energy on the vertical axis and Positive/Negative mood on the horizontal axis.",
            "owner":       REPO_OWNER,
            "versions":    [],
            "imageUrl":    f"https://raw.githubusercontent.com/{REPO_OWNER}/{REPO_NAME}/main/assets/logo.png",
        }
    ]


def save_manifest(data: list) -> None:
    with open(MANIFEST_FILE, "w") as f:
        json.dump(data, f, indent=2)
    print(f"Wrote {MANIFEST_FILE}")


def version_tuple(v: str):
    return tuple(int(x) for x in v.split("."))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version",   required=True, help="e.g. 1.0.0.0")
    parser.add_argument("--tag",       required=True, help="e.g. v1.0.0.0")
    parser.add_argument("--checksum",  required=True, help="MD5 of zip file")
    parser.add_argument("--timestamp", required=True, help="ISO 8601 UTC timestamp")
    parser.add_argument("--changelog", default="",    help="Release notes")
    args = parser.parse_args()

    artifact = f"Jellyfin.Plugin.Musian_{args.version}.zip"
    source_url = f"{BASE_URL}/{args.tag}/{artifact}"

    manifest = load_manifest()
    plugin = manifest[0]

    # Remove any existing entry for this version (idempotent re-runs)
    plugin["versions"] = [
        v for v in plugin["versions"] if v["version"] != args.version
    ]

    new_entry = {
        "version":    args.version,
        "changelog":  args.changelog or f"Release {args.version}",
        "targetAbi":  TARGET_ABI,
        "sourceUrl":  source_url,
        "checksum":   args.checksum,
        "timestamp":  args.timestamp,
    }

    plugin["versions"].append(new_entry)

    # Sort newest-first
    plugin["versions"].sort(
        key=lambda v: version_tuple(v["version"]), reverse=True
    )

    save_manifest(manifest)
    print(f"Added version {args.version}  ({source_url})")


if __name__ == "__main__":
    main()
