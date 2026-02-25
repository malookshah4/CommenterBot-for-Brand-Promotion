# CommenterBot For Brand Promotion

An Android app that automatically replies to TikTok video comments using Android's AccessibilityService. Built for creators who want to promote their apps across multiple TikTok videos at scale.

## Features

### Core
- **Auto-Reply**: Automatically opens comments on TikTok videos, replies to individual comments, and scrolls to the next video
- **Random Selection**: Picks a random enabled reply for each comment to keep responses varied
- **State Machine Workflow**: 14-state engine handles the full lifecycle — opening comments, finding reply buttons, entering text, tapping send, scrolling, and moving to the next video
- **Live Status & Logs**: Real-time status display and scrollable log panel in the app UI

### Multiple App Profiles
- Create separate profiles for each app you want to promote (e.g., "GoViral AI", "My Other App")
- Each profile has its own set of replies and keywords
- Switch between profiles with a single tap on the main screen

### Keyword Targeting
- Add keywords per profile (e.g., "viral", "followers", "grow")
- Bot only replies to comments that contain at least one keyword
- Leave keywords empty to reply to all comments (default behavior)

### Reply Templates
- Use `{app_name}` in replies — automatically replaced with the active profile name
- Use `{100-500}` for random numbers in a range — each reply gets a unique number
- Makes every reply unique even when using the same template

### Safety & Rate Limiting
- **Configurable delay** between replies (min/max seconds)
- **Hourly limit** — max replies per hour (pauses when hit)
- **Daily limit** — max replies per day (stops when hit)

### Auto-Stop
- Stop after a set number of replies per session
- Stop after a set number of minutes
- Set to 0 for unlimited (default)

## How It Works

The bot uses Android's AccessibilityService to interact with the TikTok UI:

1. Detects when TikTok is in the foreground
2. Opens the comments panel on the current video
3. Finds and taps "Reply" on individual comments (with optional keyword filtering)
4. Enters a reply message using `ACTION_SET_TEXT` (with clipboard paste fallback)
5. Locates and taps the Send button via cross-window node search (skips keyboard/IME windows)
6. Scrolls through comments, then swipes to the next video and repeats

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Min SDK**: 30 (Android 11)
- **Target SDK**: 36
- **Key APIs**: AccessibilityService, GestureDescription, AccessibilityNodeInfo

## Setup

1. Clone the repo and open in Android Studio
2. Build and install on your device
3. Open the app and go to **Accessibility Settings** to enable the CommenterBot service
4. Create a profile and add your reply messages
5. Optionally add keywords and configure settings (delays, limits, auto-stop)
6. Open TikTok, switch back to CommenterBot, and tap **Start Bot**

## App Screens

- **Main Screen** — Profile selector, start/stop bot, session/daily stats, live log
- **Profiles** — Create, edit, delete, and switch between promotion profiles
- **Profile Edit** — Manage replies and keywords for a profile
- **Settings** — Configure reply delays, rate limits, and auto-stop rules

## Permissions

- **Accessibility Service**: Required to read and interact with TikTok's UI elements

## Project Structure

```
app/src/main/java/com/codebage/commenterbot/
├── MainActivity.kt          # Compose UI — all screens (bot control, profiles, settings)
├── TikTokReplyService.kt    # Core AccessibilityService automation engine
└── ReplyManager.kt          # Profiles, replies, keywords, settings, template processing
```

## License

This project is provided as-is for educational purposes.
