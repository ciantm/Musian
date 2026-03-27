# 🎵 JellyMoods

A Jellyfin plugin that plays music from your library based on mood selection, using a visual **Valence–Arousal emotion wheel**.

Available as a community plugin — install directly from Jellyfin's plugin catalogue.

---

## Install via Plugin Repository (recommended)

1. Open Jellyfin → **Dashboard → Plugins → Repositories**
2. Click **+** and add this URL:
   ```
   https://ciantm.github.io/Jellymoods/manifest.json
   ```
3. Go to **Catalogue**, search for **JellyMoods**, and install
4. Restart Jellyfin
5. **JellyMoods** will appear in the sidebar for all users

---

## How it works

Click anywhere on the circular mood wheel — your position maps to two emotional axes:

| Axis | Direction |
|------|-----------|
| **Valence** (positive/negative) | ← negative · positive → |
| **Arousal** (energy level) | ↓ passive · active ↑ |

**Quadrant moods:**

| Quadrant | Mood | Default genres |
|----------|------|----------------|
| 🔴 Top-left  | Angry / Tense     | Metal, Punk, Hard Rock, Industrial |
| 🟡 Top-right | Happy / Excited   | Pop, Dance, Electronic, Funk, House |
| 🔵 Bottom-left  | Sad / Melancholic | Blues, Soul, Folk, Indie |
| 🟢 Bottom-right | Calm / Relaxed    | Jazz, Ambient, Classical, Acoustic |

Clicking near edges blends genres from adjacent moods automatically.

---

## Configuration

Go to **Dashboard → Plugins → JellyMoods Settings** to:

- Customise genre lists per quadrant (comma-separated, matching your library genre tags)
- Adjust playlist size (default 30 tracks)
- Adjust blend radius (how much adjacent-mood genres bleed in)
- Toggle shuffle

---

## Requirements

- Jellyfin **10.8** or later
- Audio tracks with **Genre** tags set in your library
- .NET **8.0** SDK (only needed if building from source)

---

## Publishing a new release

```bash
git tag v1.1.0.0
git push origin v1.1.0.0
```

The GitHub Actions workflow will build the DLL, create a Release, and update `manifest.json` on the `gh-pages` branch automatically.

---

## Submitting to the official Jellyfin Plugin Catalogue

To get JellyMoods listed without needing a manual repo URL:

1. Ensure the repo is public with at least one release
2. Open a PR at [jellyfin/jellyfin-plugin-repository](https://github.com/jellyfin/jellyfin-plugin-repository)
3. Follow the review checklist in that repo

---

## Build from source

```bash
git clone https://github.com/ciantm/Jellymoods.git
cd Jellymoods
dotnet build Jellyfin.Plugin.JellyMoods -c Release
```

Copy the DLL to your Jellyfin plugins folder:

- **Linux:** `~/.local/share/jellyfin/plugins/JellyMoods_1.0.0.0/`
- **Windows:** `%APPDATA%\Jellyfin\plugins\JellyMoods_1.0.0.0\`
- **Docker:** `/config/plugins/JellyMoods_1.0.0.0/`

---

## Licence

MIT
