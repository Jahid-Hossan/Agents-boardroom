# Coolify Deployment Guide

This repository is configured for Docker Compose deployment on Coolify.

## 1. Create the application

1. In Coolify, open the target Project and Environment.
2. Create an Application from the GitHub repository `Jahid-Hossan/Agents-boardroom`.
3. Select branch `main`.
4. Set Build Pack to **Docker Compose**.
5. Set Base Directory to `/` and Compose file to `/docker-compose.yml`.
6. Click **Reload Compose** after changing the repository configuration so Coolify reads the current Compose file.

## 2. Environment variables

Set `GEMINI_API_KEY` in the application's Environment Variables. The Compose file passes it into the container. Keep the key in Coolify Secrets/Environment Variables; never commit it to GitHub.

The application listens on port `3000`.

## 3. Persistent data

The Compose file declares the named volume `muse_boardroom_data` mounted at `/app/data`.

The startup script initializes and stores:
- `/app/data/config.json`
- `/app/data/messages.json`
- `/app/data/uploads/`
- `/app/data/profiles/` (Chromium login profiles)

Do not remove this volume when redeploying. Deleting the volume deletes the app's saved configuration, messages, uploaded files, and browser login profiles.

## 4. Domain and deployment

1. In the service's Domains/Public URL setting, add your chosen hostname with HTTPS, for example `https://boardroom.example.com`.
2. Ensure that the hostname's DNS record points to the VPS and that the Coolify proxy can issue TLS certificates.
3. Deploy the application.
4. After deployment, open the domain and test the dashboard, Gemini API mode, and In-App Browser.

## 5. Updating

Push changes to the `main` branch and redeploy from Coolify. Keep the named volume attached to preserve runtime data.
