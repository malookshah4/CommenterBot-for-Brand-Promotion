# CommenterBot

An Android app that automatically replies to TikTok video comments using Android's AccessibilityService.

## Features

- **Auto-Reply**: Automatically opens comments on TikTok videos, replies to individual comments, and scrolls to the next video
- **Customizable Replies**: Add, edit, delete, and toggle reply messages from the built-in management UI
- **Random Selection**: Picks a random enabled reply for each comment to keep responses varied
- **Configurable Volume**: Replies to 29–40 comments per video with randomized delays between actions
- **State Machine Workflow**: 14-state engine handles the full lifecycle — opening comments, finding reply buttons, entering text, tapping send, scrolling, and moving to the next video
- **Live Status & Logs**: Real-time status display and scrollable log panel in the app UI

## How It Works

The bot uses Android's AccessibilityService to interact with the TikTok UI:

1. Detects when TikTok is in the foreground
2. Opens the comments panel on the current video
3. Finds and taps "Reply" on individual comments
4. Enters a random reply message using `ACTION_SET_TEXT` (with clipboard paste fallback)
5. Locates and taps the Send button via cross-window node search
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
4. Add your reply messages in the **Reply Management** screen
5. Open TikTok, switch back to CommenterBot, and tap **Start Bot**

## Permissions

- **Accessibility Service**: Required to read and interact with TikTok's UI elements

## Project Structure

```
app/src/main/java/com/codebage/commenterbot/
├── MainActivity.kt          # Compose UI — bot control + reply management
├── TikTokReplyService.kt    # Core AccessibilityService automation engine
└── ReplyManager.kt          # SharedPreferences-based reply storage
```

## License

This project is provided as-is for educational purposes.
