# Flow Assistant

Flow Assistant is an Android application that integrates with Speechmatics Flow to provide real-time speech-to-speech voice interactions. The application enables natural and fluid conversations by leveraging Speechmatics' conversational AI capabilities.

All code in this application was generated using OpenAI's o1-preview model and is not an official supported implementation.

## Features

- Real-time speech-to-speech conversation
- Can replace the default Android Assistant
- Can run from the lock screen
- Automatic handling of interruptions
- Support for multiple speakers
- Understanding of different dialects and accents

## Setup Instructions

### Prerequisites

- Android Studio
- Android SDK
- Android device or emulator
- Speechmatics Flow API key

### Building the Project

1. Clone this repository
2. Open the project in Android Studio
3. Sync project with Gradle files
4. Build the project using Android Studio

### Connecting Android Device in Debug Mode

1. Enable Developer Options on your Android device:
   - Go to Settings > About Phone
   - Tap "Build Number" 7 times to enable Developer Options

2. Enable USB Debugging:
   - Go to Settings > Developer Options
   - Enable "USB Debugging"

3. Connect your device:
   - Connect your Android device to your computer via USB
   - Allow USB debugging when prompted on your device
   - Your device should appear in Android Studio's device dropdown menu

### Setting as Default Assistant

To set Flow Assistant as your default digital assistant on the latest Android phones:

1. Go to your device's Settings
2. Scroll down and tap on "Apps" or "Applications"
3. Tap on "Default apps" or "Choose default apps"
4. Look for "Digital assistant app" or "Default digital assistant app"
5. Select "Flow Assistant" from the list of available assistants
6. Optional: Enable "Use while device is locked" if you want to access the assistant from the lock screen

Note: The exact steps might vary slightly depending on your Android device manufacturer and version.

### Configuring the Application

#### Setting up the API Key

1. Launch Flow Assistant or tap the settings icon (three dots) in the top-right corner
2. In the settings menu, locate the "API Key" field
3. Enter your Speechmatics Flow API key
4. Tap "Save" to store your API key securely

Note: Your API key will be securely stored and used for all future conversations. You only need to set this once unless you want to use a different key.

#### Speaker Phone Settings

Flow Assistant includes a speaker phone toggle that controls how audio is played:

- When enabled (ON): Audio will play through the device's speaker, making it suitable for hands-free use or when you want others to hear the assistant's responses
- When disabled (OFF): Audio will play through the device's earpiece, providing more privacy for your conversations

To adjust the speaker phone setting:
1. Open Flow Assistant or tap the settings icon (three dots)
2. Find the "Use Speaker Phone" toggle
3. Switch it ON or OFF according to your preference

The speaker phone setting will be remembered for future conversations until you change it again.

## Speechmatics Flow Integration

### Getting Started with Speechmatics Flow

You can get access to try it here:

1. Create an account at [Speechmatics Portal](https://portal.speechmatics.com/signup)
2. Navigate to Manage > API Keys in the portal
3. Create a new API key and store it securely
4. Add your API key in the application settings

For more information about Speechmatics Flow, visit the [Flow Documentation](https://docs.speechmatics.com/flow/getting-started)

## Disclaimer

This code is provided "AS IS" without warranty of any kind, either express or implied, including without limitation any implied warranties of condition, uninterrupted use, merchantability, fitness for a particular purpose, or non-infringement.

All code in this application was generated using OpenAI's o1-preview model. Minimal effort has been made to ensure quality and functionality, users should review and test the code thoroughly before use in production environments.
