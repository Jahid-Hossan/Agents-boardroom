@echo off
setlocal enabledelayedexpansion
title Push to GitHub
cd /d "%~dp0"
echo ========================================================
echo   PUSH MUSE BOARDROOM TO GITHUB
echo ========================================================
echo.

where git >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Git is not installed or not in PATH.
    echo Install Git from https://git-scm.com/downloads and try again.
    pause
    exit /b 1
)

if not exist ".git" (
    echo Initializing Git repository...
    git init
)

for /f "delims=" %%i in ('git config user.name 2^>nul') do set GIT_NAME=%%i
if "!GIT_NAME!"=="" (
    git config user.name "Jahid Hossan"
)

for /f "delims=" %%i in ('git config user.email 2^>nul') do set GIT_EMAIL=%%i
if "!GIT_EMAIL!"=="" (
    git config user.email "jahidhossan.dev@gmail.com"
)

git branch -M main

echo Staging files...
git add -A

git diff --cached --quiet
if %ERRORLEVEL% NEQ 0 (
    echo Committing latest changes...
    git commit -m "Update Muse Boardroom"
) else (
    git rev-parse --verify HEAD >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        git commit --allow-empty -m "Initial commit: Muse Boardroom"
    ) else (
        echo No new file changes to commit.
    )
)

set REPO_URL=
for /f "delims=" %%i in ('git remote get-url origin 2^>nul') do set EXISTING_URL=%%i

if not "!EXISTING_URL!"=="" (
    echo Current remote origin: !EXISTING_URL!
    set /p REPO_URL="Press Enter to reuse it, or paste a new GitHub repo URL: "
    if "!REPO_URL!"=="" set REPO_URL=!EXISTING_URL!
) else (
    echo 1. Go to https://github.com/new and create an EMPTY repository
    echo 2. Copy the HTTPS URL (e.g. https://github.com/username/muse-boardroom.git)
    echo.
    set /p REPO_URL="Enter your GitHub Repository URL: "
)

if "!REPO_URL!"=="" (
    echo No URL entered. Exiting.
    pause
    exit /b 1
)

git remote remove origin 2>nul
git remote add origin "!REPO_URL!"

echo.
echo Pushing to GitHub (main)...
git push -u origin main

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Initial push failed. Checking if remote has existing commits (e.g. README)...
    git fetch origin main 2>nul
    if %ERRORLEVEL% EQU 0 (
        echo Rebasing onto remote main...
        git pull --rebase --allow-unrelated-histories origin main
        if %ERRORLEVEL% EQU 0 (
            git push -u origin main
        )
    )
)

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo SUCCESS! Repository pushed to GitHub!
    echo ========================================================
) else (
    echo.
    echo [FAILED] Could not push to GitHub. Common fixes:
    echo   1. If prompted for a password, use a GitHub Personal Access Token
    echo      (https://github.com/settings/tokens - Classic token with 'repo' scope),
    echo      NOT your GitHub account password.
    echo   2. Make sure the repository exists on GitHub and you have write access.
    echo   3. If using a new repo, leave "Add a README file" unchecked.
)

pause
