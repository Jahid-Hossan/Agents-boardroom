# Muse Boardroom — Multi-Agent AI Command Center (Android)

Native **Kotlin + Jetpack Compose** Android application for orchestrating **Muse AI**, **Google Gemini**, **ChatGPT**, **Grok**, and custom AI models from one unified command center.

---

## Core Features

- **Unified Boardroom Broadcast & Direct Chat**: Broadcast prompts to all connected agents (`@all` or `@AgentName`), or switch into dedicated 1-on-1 direct chats with a single tap.
- **Multi-Hop `@AgentName` & CEO/Supervisor Orchestration**: Designate any agent as **CEO / Supervisor** to review commands first, delegate tasks to teammates via `@AgentName: <task>`, run up to 1–5 parallel collaboration rounds, and synthesize a final team answer.
- **Built-In In-App Browser Sessions**: Sign directly into `muse.ai`, `gemini.google.com/app`, `chatgpt.com`, `grok.com`, and `claude.ai` inside persistent in-app `WebView` sessions with status detection and multi-account tabs.
- **Native Gemini AI & Custom Providers**: Uses **Firebase AI Logic** (`gemini-3.8-flash`) out of the box with zero API key setup, plus optional OpenAI / Groq / Compatible API endpoints, Webhook / Docker HTTP endpoints, and Offline Demo mode.
- **Voice-to-Prompt & Rich File Attachments**: Dictate voice prompts (`RECORD_AUDIO`) transcribed via Gemini audio understanding, or attach images, documents, and audio files using the zero-permission Android Photo Picker and Document Picker.
- **Offline Room Persistence**: Agents, collaboration settings, and boardroom chat history are persisted locally via **Room**.

---

## Architecture & Tech Stack

- **UI**: Jetpack Compose, Material 3, Adaptive `WindowWidthSizeClass` (navigation drawer on phones, persistent sidebar on tablets/foldables), custom typography (`Plus Jakarta Sans` & `JetBrains Mono`)
- **State & Architecture**: MVVM (`BoardroomViewModel`, `StateFlow`, `collectAsStateWithLifecycle`), Kotlin Coroutines
- **Local Database**: Room (`AgentEntity`, `MessageEntity`, `SettingsEntity`)
- **Cloud AI**: Firebase AI Logic + Firebase App Check
- **Networking**: OkHttp for custom OpenAI-compatible & webhook endpoints
- **In-App Web Agents**: Android `WebView` + `CookieManager` + DOM automation

---

## Build & Test

```bash
gradle assembleDebug
gradle :app:testDebugUnitTest
```
