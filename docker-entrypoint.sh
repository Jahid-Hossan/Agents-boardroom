#!/bin/sh
set -eu

DATA_DIR="${DATA_DIR:-/app/data}"
mkdir -p "$DATA_DIR/uploads" "$DATA_DIR/profiles"

# Seed persistent files once from the repository image.
if [ ! -f "$DATA_DIR/config.json" ]; then
  cp /app/config.json "$DATA_DIR/config.json"
fi

if [ ! -f "$DATA_DIR/messages.json" ]; then
  cp /app/messages.json "$DATA_DIR/messages.json"
fi

# Preserve any uploads already present in the image before linking the persistent directory.
if [ -d /app/public/uploads ] && [ ! -L /app/public/uploads ]; then
  cp -a /app/public/uploads/. "$DATA_DIR/uploads/" 2>/dev/null || true
  rm -rf /app/public/uploads
fi

# Keep the app's existing paths while storing their contents on the mounted volume.
ln -sfn "$DATA_DIR/config.json" /app/config.json
ln -sfn "$DATA_DIR/messages.json" /app/messages.json
mkdir -p /app/public
ln -sfn "$DATA_DIR/uploads" /app/public/uploads

export PROFILES_DIR="${PROFILES_DIR:-$DATA_DIR/profiles}"
mkdir -p "$PROFILES_DIR"

exec node /app/server.js
