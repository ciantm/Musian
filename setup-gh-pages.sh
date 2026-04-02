#!/usr/bin/env bash
# Run this ONCE after pushing to GitHub to set up the gh-pages branch.
# It creates an orphan branch with just manifest.json and update_manifest.py

set -e

echo "Setting up gh-pages branch..."

git checkout --orphan gh-pages
git rm -rf . --quiet

cp /dev/stdin manifest.json << 'MANIFEST'
[
  {
    "category": "Music",
    "guid": "6c8a80b7-3e2f-4d5a-9b1c-f7e8d9a0b2c3",
    "name": "Musian",
    "description": "Play music from your Jellyfin library by selecting a mood on a visual emotion wheel.",
    "overview": "Click anywhere on the colour wheel to match music to how you feel.",
    "owner": "ciantm",
    "imageUrl": "https://raw.githubusercontent.com/ciantm/Musian/main/assets/logo.png",
    "versions": []
  }
]
MANIFEST

# Copy the manifest update script
git show main:update_manifest.py > update_manifest.py

git add manifest.json update_manifest.py
git commit -m "chore: initialise gh-pages with empty manifest"
git push origin gh-pages

echo "Done! gh-pages branch created."
echo "Now go to: GitHub repo → Settings → Pages → Source: gh-pages branch"
echo "Your manifest will be at: https://ciantm.github.io/Musian/manifest.json"

git checkout main
