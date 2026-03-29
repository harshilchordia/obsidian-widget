# Obsidian Widget for Android

A feature-rich Android home screen widget for [Obsidian](https://obsidian.md). View your notes, check off tasks, and capture ideas — all without opening the app.

<p align="center">
   <img src="assets/screenshots/widget-shopping-list.png" alt="Home screen widget showing a shopping list" width="24%" />
   <img src="assets/screenshots/widget-todo.png" alt="Compact home screen widget showing a to-do list" width="24%" />
   <img src="assets/screenshots/configure-widget-top.png" alt="Widget configuration screen with vault and note mode settings" width="24%" />
   <img src="assets/screenshots/configure-widget-full.png" alt="Widget configuration screen with theme, accent color, and opacity" width="24%" />
</p>


## Install

### Option 1: Download the APK (easiest)

1. Go to the [Releases](../../releases) page
2. Download the latest `.apk` file
3. On your Android device, open the APK and tap **Install**
   - You may need to enable "Install from unknown sources" in Settings

### Option 2: Build from source

```bash
git clone https://github.com/YOUR_USERNAME/obsidian-widget.git
cd obsidian-widget
./gradlew assembleRelease
# APK will be at app/build/outputs/apk/release/app-release-unsigned.apk
```

Or open in Android Studio and click **Run**.

## Setup

1. Open the **Obsidian Widget** app and select your vault folder
2. Long-press your home screen → **Widgets** → drag **Obsidian Widget**
3. Tap the **⚙** gear icon on the widget to configure it

## Features

- **Daily note or pinned notes** — show today's daily note or pick specific files
- **Multiple notes per widget** — swipe between notes with navigation arrows
- **Interactive checkboxes** — tap to toggle `- [ ]` / `- [x]` directly from the widget
- **Quick capture** — pop-up dialog to append text to your note
- **Append with +** — one-tap add, auto-formats as checkbox if the note uses them
- **Markdown rendering** — bold, italic, headings, and bullet lists
- **Sort unchecked first** — push completed tasks to the bottom
- **Widget transparency** — adjustable opacity to blend with your wallpaper
- **Auto-refresh** — updates every 30 minutes and on screen unlock
- **Deep link to Obsidian** — tap the title to open the note in Obsidian
- **Per-widget settings** — each widget has independent configuration
- **Dark theme** — Obsidian-inspired dark UI

## Widget Configuration

All settings are per-widget. Tap the ⚙ icon on any widget to access:

| Setting | Description |
|---------|-------------|
| Vault | Select your Obsidian vault folder |
| Note Mode | Daily note or specific note(s) |
| Notes | Add/remove multiple pinned notes |
| Daily Subfolder | e.g. `Daily Notes` |
| Date Format | Any Java date pattern (`yyyy-MM-dd`, `dd-MM-yyyy`, etc.) |
| Show Buttons | Toggle the capture/add button bar |
| Sort Unchecked | Move incomplete tasks to the top |
| Widget Opacity | 0–100% transparency slider |

## Requirements

- Android 8.0+ (API 26)
- Obsidian vault accessible on device (local or synced)
- No root or special permissions needed — uses Android's Storage Access Framework

## Project Structure

```
app/src/main/java/com/obsidianwidget/
├── MainActivity.kt              # Welcome screen & vault selection
├── WidgetConfigActivity.kt      # Per-widget settings (gear icon)
├── ObsidianWidgetProvider.kt    # Widget rendering & action handling
├── ChecklistWidgetService.kt    # ListView adapter (checkboxes, text, headings)
├── QuickCaptureActivity.kt      # Quick capture dialog
└── VaultManager.kt              # Vault I/O, preferences, markdown parsing
```

## Tech Stack

- **Kotlin** + **AndroidX**
- **Storage Access Framework** — secure file access, no broad storage permissions
- **AppWidgetProvider** + **RemoteViewsService** — native widget framework
- **SharedPreferences** — per-widget settings with `_widgetId` key suffixing

## License

MIT
