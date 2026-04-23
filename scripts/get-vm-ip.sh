#!/usr/bin/env bash
set -euo pipefail

VM_NAME="${1:-}"
VIRT_HOST="${2:-89.104.68.81}"

if [[ -z "$VM_NAME" ]]; then
  echo "Usage: $0 <vm_name> [virt_host]"
  exit 1
fi

ssh_run() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 \
    "root@${VIRT_HOST}" -- "$@" 2>/dev/null || true
}

extract_ipv4_from_domifaddr() {
  awk 'tolower($0) ~ /ipv4/ { split($4, cidr, "/"); print cidr[1]; exit }'
}

ip_from_domifaddr_source() {
  local source="$1"
  ssh_run virsh domifaddr "${VM_NAME}" --source "${source}" | extract_ipv4_from_domifaddr
}

ip_from_net_dhcp_leases() {
  local mac
  mac="$(ssh_run virsh domiflist "${VM_NAME}" | awk 'NR>2 && NF>=5 { print tolower($5); exit }')"
  if [[ -z "$mac" ]]; then
    return 0
  fi

  ssh_run virsh net-dhcp-leases default | awk -v mac="$mac" '
    tolower($0) ~ mac {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+\/[0-9]+$/) {
          split($i, cidr, "/");
          print cidr[1];
          exit
        }
      }
    }
  '
}

IP="$(ip_from_domifaddr_source lease)"
if [[ -z "$IP" ]]; then
  IP="$(ip_from_domifaddr_source arp)"
fi
if [[ -z "$IP" ]]; then
  IP="$(ip_from_domifaddr_source agent)"
fi
if [[ -z "$IP" ]]; then
  IP="$(ip_from_net_dhcp_leases)"
fi
if [[ -z "$IP" ]]; then
  IP="$(ssh_run virsh domifaddr "${VM_NAME}" | extract_ipv4_from_domifaddr)"
fi

if [[ -n "$IP" ]]; then
  echo "$IP"
else
  echo "pending"
fi
