FROM node:22-alpine

WORKDIR /app

# Install Chromium and required system libraries/fonts for headless browser support
RUN apk add --no-cache \
  chromium \
  nss \
  freetype \
  harfbuzz \
  ca-certificates \
  ttf-freefont

ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true

# Copy package files
COPY package*.json ./

# Install production dependencies
RUN npm install --omit=dev

# Copy application files
COPY . .

# Prepare persistent-storage startup script
RUN chmod +x /app/docker-entrypoint.sh

# Expose web port
EXPOSE 3000

# Start with persistent storage initialization
CMD ["/app/docker-entrypoint.sh"]
