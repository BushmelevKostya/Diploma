#!/usr/bin/env bash
set -euo pipefail

VM_NAME="${1:-}"
VIRT_HOST="${VIRT_HOST:-${2:-89.104.68.81}}"
DHCP_NET="${LIBVIRT_DHCP_NETWORK:-default}"

if [[ -z "$VM_NAME" ]]; then
  echo "Usage: $0 <vm_name> [virt_host]" >&2
  echo "Optional: LIBVIRT_SSH_IDENTITY, VIRT_HOST; LIBVIRT_DHCP_NETWORK (default: default) for net-dhcp-leases." >&2
  exit 1
fi

ssh_run() {
  local -a id=()
  if [[ -n "${LIBVIRT_SSH_IDENTITY:-}" && -r "${LIBVIRT_SSH_IDENTITY}" ]]; then
    id+=(-i "${LIBVIRT_SSH_IDENTITY}")
  fi
  ssh "${id[@]}" -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 \
    "root@${VIRT_HOST}" -- "$@" || true
}

# virsh column layout varies; first token matching IPv4/CIDR on non-header lines.
extract_ipv4_from_domifaddr() {
  awk '
    /^[[:space:]]*$/ { next }
    /^[Nn]ame[[:space:]]/ { next }
    /^--+/ { next }
    {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+\/[0-9]+$/) {
          split($i, cidr, "/");
          print cidr[1];
          exit;
        }
      }
    }
  '
}

ip_from_domifaddr_source() {
  local source="$1"
  if [[ "$source" == "agent" ]]; then
    # Until qemu-ga connects, libvirt prints errors every call; hide stderr for this probe only.
    ssh_run sh -c "virsh domifaddr $(printf '%q' "${VM_NAME}") --source agent 2>/dev/null" | extract_ipv4_from_domifaddr
  else
    ssh_run virsh domifaddr "${VM_NAME}" --source "${source}" | extract_ipv4_from_domifaddr
  fi
}

ip_from_net_dhcp_leases() {
  local mac
  mac="$(ssh_run virsh domiflist "${VM_NAME}" | awk '
    NR > 2 {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$/) {
          print tolower($i);
          exit;
        }
      }
    }
  ')"
  if [[ -z "$mac" ]]; then
    return 0
  fi

  ssh_run virsh net-dhcp-leases "${DHCP_NET}" | awk -v mac="$mac" '
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

# Prefer libvirt DHCP lease table early (often shows IP before domifaddr lease is populated).
IP="$(ip_from_domifaddr_source lease)"
if [[ -z "$IP" ]]; then
  IP="$(ip_from_domifaddr_source arp)"
fi
if [[ -z "$IP" ]]; then
  IP="$(ip_from_net_dhcp_leases)"
fi
if [[ -z "$IP" ]]; then
  IP="$(ip_from_domifaddr_source agent)"
fi
if [[ -z "$IP" ]]; then
  IP="$(ssh_run virsh domifaddr "${VM_NAME}" | extract_ipv4_from_domifaddr)"
fi

if [[ -n "$IP" ]]; then
  echo "$IP"
else
  echo "pending"
fi
