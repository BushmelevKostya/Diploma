terraform {
  required_providers {
    libvirt = {
      source  = "dmacvicar/libvirt"
      version = "0.9.7"
    }
  }
}

locals {
  is_alpine = startswith(var.os_image, "alpine")

  ubuntu_user_data = <<-EOF
#cloud-config
hostname: ${var.hostname}
users:
  - name: ubuntu
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    lock_passwd: false
    plain_text_passwd: temppass123
    ssh_authorized_keys:
      - ${var.ssh_public_key}
disable_root: false
ssh_authorized_keys:
  - ${var.ssh_public_key}
ssh_pwauth: false
growpart:
  mode: auto
resize_rootfs: true

apt:
  primary:
    - arches: [default]
      uri: http://mirror.yandex.ru/ubuntu
  security:
    - arches: [default]
      uri: http://mirror.yandex.ru/ubuntu

write_files:
  - path: /etc/apt/apt.conf.d/99force-ipv4
    content: |
      Acquire::ForceIPv4 "true";
EOF

  alpine_user_data = <<-EOF
#cloud-config
hostname: ${var.hostname}
users:
  - name: alpine
    shell: /bin/sh
    lock_passwd: false
    plain_text_passwd: temppass123
    ssh_authorized_keys:
      - ${var.ssh_public_key}
ssh_authorized_keys:
  - ${var.ssh_public_key}
ssh_pwauth: false

runcmd:
  - [ sh, -c, "apk update || true" ]
  - [ sh, -c, "apk add --no-cache python3 openssh sudo qemu-guest-agent || true" ]
  - [ sh, -c, "rc-update add sshd default || true" ]
  - [ sh, -c, "rc-service sshd start || true" ]
  - [ sh, -c, "rc-update add qemu-guest-agent default || true" ]
  - [ sh, -c, "rc-service qemu-guest-agent start || true" ]
  - [ sh, -c, "echo 'alpine ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/alpine && chmod 0440 /etc/sudoers.d/alpine" ]
EOF

  selected_user_data = local.is_alpine ? local.alpine_user_data : local.ubuntu_user_data

  ubuntu_network_config = <<-EOF
version: 2
ethernets:
  nic0:
    match:
      name: "en*"
    dhcp4: true
EOF

  alpine_network_config = <<-EOF
version: 2
ethernets:
  eth0:
    dhcp4: true
EOF

  selected_network_config = local.is_alpine ? local.alpine_network_config : local.ubuntu_network_config
}

resource "libvirt_cloudinit_disk" "init" {
  name           = "${var.name}-cloudinit.iso"
  user_data      = local.selected_user_data
  network_config = local.selected_network_config

  meta_data = <<-EOF
instance-id: ${var.name}
local-hostname: ${var.name}
EOF
}

resource "libvirt_volume" "base" {
  name   = "${var.name}-base.qcow2"
  pool   = var.storage_pool
  source = var.base_image_path
  format = "qcow2"
}

resource "libvirt_volume" "root" {
  name           = "${var.name}.qcow2"
  pool           = var.storage_pool
  base_volume_id = libvirt_volume.base.id
  size           = var.disk_size_gb * 1024 * 1024 * 1024
  format         = "qcow2"
}

resource "libvirt_domain" "vm" {
  name      = var.name
  memory    = var.memory
  vcpu      = var.vcpu
  autostart = true

  cloudinit = libvirt_cloudinit_disk.init.id

  network_interface {
    network_name   = var.network_name
    wait_for_lease = false
  }

  disk {
    volume_id = libvirt_volume.root.id
  }

  console {
    type        = "pty"
    target_type = "serial"
    target_port = "0"
  }

  console {
    type        = "pty"
    target_type = "virtio"
    target_port = "1"
  }

  graphics {
    type        = "vnc"
    listen_type = "address"
  }

  qemu_agent = true
}

output "id" {
  value = libvirt_domain.vm.id
}
