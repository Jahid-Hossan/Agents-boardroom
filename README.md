# Muse Boardroom — Multi-Agent AI Command Center

Command **Muse AI**, **Google Gemini**, **ChatGPT**, **Grok**, and custom AI models from a single unified chat dashboard. Broadcast prompts to all connected agents at once or switch to 1-on-1 direct threads, dictate prompts by voice, and attach images, documents, or audio files.

---

## Key Features

- **Unified Boardroom & Direct Chat**: Broadcast one prompt to every connected agent (`@all` or `@AgentName`), or switch into dedicated 1-on-1 chats.
- **Multiple Connections per AI**: Add multiple isolated accounts for the same AI platform (e.g., `Muse AI 1`, `Muse AI 2`, `Gemini Work`, `Gemini Personal`).
- **Built-In In-App Browser & Attached Connector**: Sign in directly inside the workspace modal (`https://muse.ai`, `https://gemini.google.com/app`, `https://chatgpt.com`, `https://grok.com`) with isolated persistent browser profiles.
- **Desktop Chrome Extension Bridge**: Download the pre-configured MV3 Chrome extension to connect your existing logged-in Desktop Chrome profiles/tabs.
- **API Key Mode**: Connect directly via Google Gemini (`@google/genai`) or OpenAI-compatible endpoints (OpenAI, Groq, OpenRouter, Ollama).
- **Voice-to-Prompt**: Dictate prompts directly into the composer using Gemini audio transcription (`POST /api/voice/transcribe`, with browser speech fallback).
- **Rich File Attachments**: Attach or drag-and-drop images, PDFs, text/code files, and audio notes cleanly across agents.

---

## Local Quick Start

Requires **Node.js >= 22.12.0**.

```bash
npm install
cp .env.example .env   # Add GEMINI_API_KEY=... for Gemini API & Voice-to-Prompt
npm start
```

Open **http://localhost:3000**.

---

## Deploying to Vercel

This repository includes a Vercel serverless entrypoint (`api/[...slug].js`), static asset serving (`public/`), and `vercel.json`.

### Step-by-Step

1. **Push this repository to GitHub** (using the AI Studio GitHub button, `git push`, or `push-to-github.bat` on Windows).
2. Go to **[vercel.com/new](https://vercel.com/new)** and import your GitHub repository.
3. Keep the default settings:
   - **Framework Preset**: `Other`
   - **Build Command**: *(leave empty or `npm run build`)*
   - **Output Directory**: `public`
4. Add **Environment Variables** in Vercel Project Settings:
   - `GEMINI_API_KEY` — Your Google Gemini API key (required for Gemini API agents and Voice-to-Prompt).
5. Click **Deploy**.

### Important: What Works on Vercel vs. Container Hosting

Vercel runs as **stateless serverless functions**, which affects browser-based features:

| Feature | Vercel (Serverless) | Docker / Coolify / Railway / VPS |
| :--- | :--- | :--- |
| **Dashboard UI, Broadcast & 1-on-1 Routing** | Works | Works |
| **Google Gemini API / OpenAI / Groq / Custom API Keys** | Works | Works |
| **Voice-to-Prompt & File Attachments** | Works | Works |
| **In-App Browser (Persistent Chromium Logins)** | **Not supported** (ephemeral `/tmp`, no persistent Chromium processes or shared OS libs) | **Full support** (Chromium + persistent volumes) |
| **Desktop Chrome Extension Bridge** | **Limited** (in-memory pairing/long-poll state is not shared across serverless instances) | **Full support** (single persistent Node process) |
| **Saved Config & Chat History (`config.json`, `messages.json`)** | Temporary (`/tmp` resets on cold start) | **Persistent** on disk |

> **Recommendation**: Use **Vercel** if you primarily use **API Key** mode (Gemini API, OpenAI, Groq, etc.). If you rely on **In-App Browser logins** (`muse.ai`, `gemini.google.com`, `chatgpt.com`, `grok.com`) or the **Desktop Chrome Extension Bridge**, deploy with **Docker / Coolify / Railway / Render / Fly.io** instead.

---

## Deploying with Full Features (Docker / Coolify / Railway / Render / VPS)

The included `Dockerfile` installs Node 22, **Chromium**, NSS, and fonts out of the box.

```bash
docker build -t muse-boardroom .
docker run -d \
  -p 3000:3000 \
  -e GEMINI_API_KEY="your-key-here" \
  -e PROFILES_DIR="/app/profiles" \
  -v $(pwd)/profiles:/app/profiles \
  --name muse-boardroom \
  muse-boardroom
```

- **Coolify**: Connect your GitHub repo, set Build Pack to **Dockerfile**, expose port `3000`, add a persistent volume for `/app/profiles` (with `PROFILES_DIR=/app/profiles`), and add `GEMINI_API_KEY` in Environment Variables. See `COOLIFY_GUIDE.md` for details.
- **Railway / Render**: Connect your GitHub repo — both platforms auto-detect the `Dockerfile` and support persistent disks for `/app/profiles`.

---

## Tests

```bash
npm run lint
npm test
```
