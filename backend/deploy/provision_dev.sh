#!/bin/bash
# One-time setup of the DEV instance on the production box (safe to re-run).
#
#   dev code : ~/english_bite_dev           (git branch "dev")
#   dev data : ~/english_bite_dev_data      (own users.db, own copy of catalog/cache, own crashes.jsonl)
#   dev API  : 127.0.0.1:8001 -> https://dev.184-193-203-68.sslip.io (nginx + Let's Encrypt)
#   dev admin token: different from production's, so the PC's sync can never publish to dev.
#
# It reuses production's Python venv (same dependencies), so it costs no extra disk or money.
set -eu
HOST=dev.184-193-203-68.sslip.io
REPO=https://github.com/hwseol/english_bite.git
PROD_TRANSLATE=$HOME/english_bite/backend/translate
DEV_DIR=$HOME/english_bite_dev
DEV_DATA=$HOME/english_bite_dev_data

echo "== code (branch dev)"
if [ ! -d "$DEV_DIR/.git" ]; then git clone -q "$REPO" "$DEV_DIR"; fi
cd "$DEV_DIR" && git fetch -q origin && git checkout -q -B dev origin/dev && echo "dev at $(git log --oneline -1)"

echo "== data (copied once from production, then independent)"
mkdir -p "$DEV_DATA"
[ -f "$DEV_DATA/catalog.json" ] || cp "$PROD_TRANSLATE/catalog.json" "$DEV_DATA/catalog.json"
[ -d "$DEV_DATA/cache" ] || cp -r "$PROD_TRANSLATE/cache" "$DEV_DATA/cache"

echo "== systemd unit"
if [ ! -f /etc/systemd/system/englishbite-api-dev.service ]; then
  TOKEN=$(openssl rand -hex 24)
  sudo tee /etc/systemd/system/englishbite-api-dev.service >/dev/null <<UNIT
[Unit]
Description=EnglishBite API (DEV instance)
After=network.target

[Service]
User=ec2-user
WorkingDirectory=$DEV_DIR/backend/translate
Environment=EB_DATA_DIR=$DEV_DATA
Environment=ADMIN_TOKEN=$TOKEN
ExecStart=$PROD_TRANSLATE/.venv/bin/python -m uvicorn server:app --host 127.0.0.1 --port 8001
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
  sudo chmod 600 /etc/systemd/system/englishbite-api-dev.service
fi
sudo systemctl daemon-reload
sudo systemctl enable --now englishbite-api-dev >/dev/null 2>&1
sudo systemctl restart englishbite-api-dev
sleep 3
systemctl is-active englishbite-api-dev
curl -s http://127.0.0.1:8001/health

echo "== nginx + certificate for $HOST"
if [ ! -f /etc/nginx/conf.d/englishbite-dev.conf ]; then
  sudo tee /etc/nginx/conf.d/englishbite-dev.conf >/dev/null <<NGINX
server {
    server_name $HOST;
    location /auth/ {
        limit_req zone=auth burst=10 nodelay;
        proxy_pass http://127.0.0.1:8001;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location /telemetry/ {
        limit_req zone=telemetry burst=5 nodelay;
        proxy_pass http://127.0.0.1:8001;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location / {
        proxy_pass http://127.0.0.1:8001;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
        client_max_body_size 20M;
    }
    listen 80;
}
NGINX
  sudo nginx -t && sudo systemctl reload nginx
  sudo certbot --nginx -d "$HOST" --non-interactive --agree-tos -m mhmh2090@gmail.com --redirect
fi
sudo nginx -t && echo "nginx ok"
