#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VM_NAME="${1:-vm1}"
VIRT_HOST="${2:-89.104.68.81}"
OUT_FILE="$ROOT_DIR/ansible/inventory/hosts.yml"

IP="$(ssh "root@${VIRT_HOST}" "virsh domifaddr ${VM_NAME} 2>/dev/null" | grep -oE '\b([0-9]{1,3}\.){3}[0-9]{1,3}' | head -1 || true)"

if [[ -z "$IP" ]]; then
  echo "ERROR: Не удалось получить IP для ${VM_NAME} через virsh domifaddr"
  exit 1
fi

cat > "$OUT_FILE" <<EOF
all:
  children:
    k3s_master:
      hosts:
        ${VM_NAME}:
          ansible_host: ${IP}
          ansible_user: ubuntu
          ansible_ssh_private_key_file: /home/kostik/.ssh/id_rsa_vm
          ansible_ssh_common_args: '-o ProxyJump=root@${VIRT_HOST}'
EOF

echo "Inventory updated: ${OUT_FILE}"
echo "${VM_NAME} -> ${IP}"
