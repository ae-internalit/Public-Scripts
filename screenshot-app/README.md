# DoubleTap Screenshot

An Android app that lets you take a screenshot by double-tapping anywhere on your screen. No buttons, no overlays — just enable it and go.

## How It Works

The app registers an Accessibility Service that places an invisible 1x1 pixel touch detector. It monitors screen touches and detects double-tap patterns. When a double-tap is detected, it triggers Android's built-in screenshot function.

## Requirements

- Android 9 (API 28) or higher

## Setup

1. Install and open the app
2. Tap **Enable Accessibility Service**
3. Find "DoubleTap Screenshot" in the list and toggle it ON
4. Confirm the permission dialog
5. Done — double-tap anywhere on your screen to take a screenshot

## Features

- **Completely invisible** — nothing added to your screen
- **Works everywhere** — any app, home screen, anywhere
- **Haptic feedback** — short vibration confirms the capture
- **Adjustable sensitivity** — Fast / Normal / Relaxed double-tap speed
- **Pause/Resume** — disable detection without removing the service
- **2-second cooldown** — prevents accidental rapid screenshots
- **No data collection** — everything stays on your device

## Building

Open the `screenshot-app` directory in Android Studio and build/run to your device.
