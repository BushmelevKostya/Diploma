#!/usr/bin/env bash
set -euo pipefail

VM_NAME="${1:-}"
VIRT_HOST="${2:-89.104.68.81}"

if [[ -z "$VM_NAME" ]]; then
  echo "Usage: $0 <vm_name> [virt_host]"
  exit 1
fi

IP="$(
  ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 \
    "root@${VIRT_HOST}" -- virsh domifaddr "${VM_NAME}" 2>/dev/null \
    | grep -oE '\b([0-9]{1,3}\.){3}[0-9]{1,3}' \
    | head -1 || true
)"

if [[ -n "$IP" ]]; then
  echo "$IP"
else
  echo "pending"
fi
