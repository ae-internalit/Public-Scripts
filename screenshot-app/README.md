# DoubleTap Screenshot

An Android app that places a small floating button on your screen. Double-tap it to take a screenshot instantly.

## How It Works

1. The app registers an **Accessibility Service** that creates a small, draggable floating button
2. **Double-tap** the button to take a screenshot using Android's built-in screenshot mechanism
3. The button briefly hides during capture so it doesn't appear in your screenshots
4. A short vibration confirms the capture

## Requirements

- Android 9 (API 28) or higher

## Setup

1. Open the app
2. Tap **Enable Accessibility Service**
3. Find "DoubleTap Screenshot" in the list and toggle it ON
4. Confirm the permission dialog
5. A blue floating button appears — double-tap it anytime to screenshot

## Features

- **Draggable** — move the button anywhere on screen
- **Semi-transparent** — doesn't obstruct your view
- **Auto-hides** during capture — the button won't appear in screenshots
- **Haptic feedback** — short vibration confirms capture
- **No data collection** — everything stays on your device

## Building

Open the `screenshot-app` directory in Android Studio and build/run to your device.
