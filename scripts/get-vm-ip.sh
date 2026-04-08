#!/bin/bash
# Получить IP VM по имени через SSH на хост виртуализации
# Использование: ./scripts/get-vm-ip.sh vm1

VM_NAME=$1
VIRT_HOST="89.104.68.81"

if [ -z "$VM_NAME" ]; then
  echo "Usage: $0 <vm_name>"
  exit 1
fi

# Попытаться получить IP через virsh domifaddr
IP=$(ssh "root@$VIRT_HOST" "virsh domifaddr $VM_NAME 2>/dev/null" | grep -oE '\b([0-9]{1,3}\.){3}[0-9]{1,3}' | head -1)

if [ -n "$IP" ]; then
  echo "$IP"
else
  echo "pending"
fi
