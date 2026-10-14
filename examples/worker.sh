#!/bin/zsh
set -eu
trap 'echo "Worker stopped"; exit 0' TERM INT
echo "Worker ready"
while true; do
  echo "$(date '+%H:%M:%S') · checking for work"
  sleep 4
done
