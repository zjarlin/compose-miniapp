#!/bin/bash
# 在 252 上构建并重启演示 API。由本地 push 后通过 SSH 执行。
set -e
cd /opt/compose-miniapp
./build-server.sh
docker compose up -d --build
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:19092/ready >/dev/null; then
    echo "✔ 252 compose-miniapp API ready"
    curl -fsS http://127.0.0.1:19092/ready
    echo
    exit 0
  fi
  sleep 1
done
echo "252 API did not become ready" >&2
docker compose logs --tail=100
exit 1
