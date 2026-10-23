#!/usr/bin/env bash
# Pakai: bash push.sh nama-repo
set -e
REPO="${1:-vinz-wifi-analyzer}"

git init -q
git checkout -B main
git add -A
git commit -qm "WiFi Analyzer - first commit" || true

gh repo create "$REPO" --public --source=. --remote=origin --push

echo ">> Nunggu workflow jalan..."
sleep 10
RUN_ID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status

echo ">> Download APK..."
rm -rf apk && mkdir apk
gh run download "$RUN_ID" -n VinzWifiAnalyzer-apk -D ./apk
echo ">> Selesai! APK ada di folder ./apk"
ls -la apk
