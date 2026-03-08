FROM node:22-bookworm-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_BREAK_SYSTEM_PACKAGES=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install backend dependencies first for better layer caching.
COPY backend/package*.json /app/backend/
RUN npm --prefix /app/backend ci --omit=dev

# Install bot dependencies.
COPY discord-bot/requirements.txt /app/discord-bot/requirements.txt
RUN python3 -m pip install --no-cache-dir -r /app/discord-bot/requirements.txt

# Copy source after dependency layers.
COPY backend /app/backend
COPY discord-bot /app/discord-bot
COPY deploy/huggingface/entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh \
    && mkdir -p /data/media

ENV HOST=0.0.0.0
ENV PORT=7860
ENV DB_PATH=/data/adex.db
ENV MEDIA_DIR=/data/media

EXPOSE 7860

CMD ["/app/entrypoint.sh"]
