# Obsidian Widget for Android

A simple Android home screen widget for [Obsidian](https://obsidian.md) that lets you:

- 📝 **View your daily note** right on the home screen
- ✏️ **Quick capture** thoughts and ideas into your daily note
- 📂 **Open Obsidian** with one tap
- 🔄 **Auto-refresh** every 30 minutes

## Screenshots

The widget displays your daily note content with a dark Obsidian-themed UI.

## Features

| Feature | Description |
|---------|-------------|
| Daily Note Preview | Shows today's note content on the widget |
| Quick Capture | Pop-up dialog to append text to today's note |
| Open Obsidian | One-tap launch of the Obsidian app |
| Vault Selection | Pick your vault folder via system file picker |
| Custom Date Format | Configure `yyyy-MM-dd` or any Java date pattern |
| Daily Notes Subfolder | Specify a subfolder like `Daily Notes` |

## Setup

### Prerequisites
- Android Studio (Hedgehog or later)
- Android SDK 34
- JDK 17
- An Obsidian vault synced to your Android device

### Build & Install

1. Open this project in Android Studio
2. Connect your Android device or start an emulator
3. Click **Run** (or `./gradlew installDebug`)

### Configure

1. Open the **Obsidian Widget** app
2. Tap **Select** to choose your Obsidian vault folder
3. (Optional) Set a daily notes subfolder (e.g., `Daily Notes`)
4. (Optional) Change the date format
5. Long-press your home screen → **Widgets** → drag **Obsidian Widget**

## How It Works

- Uses Android's **Storage Access Framework (SAF)** to read/write markdown files
- Persistable URI permissions keep access across reboots
- The widget updates via `AppWidgetProvider` every 30 minutes
- Quick Capture appends text to today's daily note (creates it if needed)

## Project Structure

```
app/src/main/
├── java/com/obsidianwidget/
│   ├── MainActivity.kt           # Main settings screen
│   ├── ObsidianWidgetProvider.kt  # Widget logic & updates
│   ├── QuickCaptureActivity.kt    # Quick capture dialog
│   ├── VaultManager.kt           # Vault read/write operations
│   └── WidgetConfigActivity.kt    # Widget configuration
├── res/
│   ├── layout/                    # UI layouts
│   ├── drawable/                  # Backgrounds & icons
│   ├── values/                    # Colors, strings, themes
│   └── xml/widget_info.xml        # Widget metadata
└── AndroidManifest.xml
```

## Tech Stack

- **Kotlin** — primary language
- **AndroidX** — AppCompat, DocumentFile, Preferences
- **Material 3** — theming
- **SAF** — secure vault file access (no broad storage permissions needed)

## License

MIT
