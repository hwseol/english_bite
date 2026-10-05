#!/bin/bash
# Deploy the API on the server:   bash ~/english_bite/backend/deploy/deploy.sh dev|prod
#
#   dev  - pulls branch "dev" into ~/english_bite_dev and restarts the dev instance
#   prod - fast-forwards ~/english_bite to origin/main and restarts production
#
# Both refuse to continue if the service doesn't come back up, so a broken deploy is noticed
# immediately rather than by a tester.
set -eu
case "${1:-}" in
  dev)
    cd ~/english_bite_dev && git fetch -q origin && git checkout -q dev && git reset -q --hard origin/dev
    SERVICE=englishbite-api-dev; PORT=8001 ;;
  prod)
    cd ~/english_bite && git pull -q --ff-only
    SERVICE=englishbite-api; PORT=8000 ;;
  *) echo "usage: deploy.sh dev|prod"; exit 2 ;;
esac
echo "$1 now at: $(git log --oneline -1)"
sudo systemctl restart "$SERVICE"
sleep 3
systemctl is-active --quiet "$SERVICE" || { echo "!! $SERVICE failed to start"; sudo journalctl -u "$SERVICE" -n 20 --no-pager; exit 1; }
curl -fsS "http://127.0.0.1:$PORT/health" && echo
