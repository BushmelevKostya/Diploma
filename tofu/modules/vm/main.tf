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
  alpine_lab_password = "temppass123"

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
    plain_text_passwd: ${local.alpine_lab_password}
    ssh_authorized_keys:
      - ${var.ssh_public_key}
ssh_authorized_keys:
  - ${var.ssh_public_key}
ssh_pwauth: false

# Let Ansible use doas before sudo is guaranteed (Alpine 3.19+ images often ship without sudo).
write_files:
  - path: /etc/doas.d/99-diploma.conf
    permissions: '0644'
    content: |
      permit nopass keepenv alpine as root

runcmd:
  # Prefer a regional mirror (same idea as Ubuntu + Yandex); avoids slow/stuck dl-cdn from lab networks.
  - [ sh, -c, ". /etc/os-release && ver=v$${VERSION_ID%.*} && printf '%s\\n' \"http://mirror.yandex.ru/mirrors/alpine/$${ver}/main\" \"http://mirror.yandex.ru/mirrors/alpine/$${ver}/community\" > /etc/apk/repositories" ]
  # "su -" asks for root's password, not alpine's; align root password with alpine for console/rescue (lab only).
  - [ sh, -c, "echo 'root:${local.alpine_lab_password}' | chpasswd" ]
  # Retry apk index: early boot can race DNS/mirrors; do not mask failures on the install line.
  - [ sh, -c, "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do apk update && break; sleep 3; done" ]
  - [ sh, -c, "apk add --no-cache doas sudo python3 openssh qemu-guest-agent" ]
  - [ sh, -c, "echo 'alpine ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/alpine && chmod 0440 /etc/sudoers.d/alpine" ]
  - [ sh, -c, "rc-update add sshd default || true" ]
  - [ sh, -c, "rc-service sshd start || true" ]
  - [ sh, -c, "rc-update add qemu-guest-agent default || true" ]
  - [ sh, -c, "rc-service qemu-guest-agent start || true" ]
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

  # Match virtio predictable names (enp*, ens*) and classic eth0; fixed "eth0" alone often misses DHCP.
  alpine_network_config = <<-EOF
version: 2
ethernets:
  nic0:
    match:
      name: "e*"
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

resource "libvirt_volume" "cloudinit" {
  name = "${var.name}-cloudinit.iso"
  pool = var.storage_pool

  lifecycle {
    ignore_changes = [create]
  }

  create = {
    content = {
      url = libvirt_cloudinit_disk.init.path
    }
  }
}

resource "libvirt_volume" "disk" {
  name     = "${var.name}-disk.qcow2"
  pool     = var.storage_pool
  capacity = var.disk_size_gb * 1024 * 1024 * 1024
  target = {
    permissions = {
      mode = "666"
    }
    format = {
      type = "qcow2"
    }
  }
  backing_store = {
    path = var.base_image_path
    format = {
      type = "qcow2"
    }
  }
}

resource "libvirt_domain" "vm" {
  name    = var.name
  memory  = var.memory
  vcpu    = var.vcpu
  running = true
  type    = "kvm"

  features = {
    acpi = true
    apic = {}
  }

  os = {
    type         = "hvm"
    type_arch    = "x86_64"
    type_machine = "q35"
  }

  devices = {
    disks = [
      {
        source = {
          volume = {
            pool   = libvirt_volume.disk.pool
            volume = libvirt_volume.disk.name
          }
        }
        target = {
          bus = "virtio"
          dev = "vda"
        }
        driver = {
          type = "qcow2"
        }
      },
      {
        device = "cdrom"
        source = {
          volume = {
            pool   = libvirt_volume.cloudinit.pool
            volume = libvirt_volume.cloudinit.name
          }
        }
        target = {
          bus = "sata"
          dev = "sda"
        }
      }
    ]

    interfaces = [
      {
        type = "network"
        model = {
          type = "virtio"
        }
        source = {
          network = {
            network = var.network_name
          }
        }
      }
    ]

    # virtio channel so libvirt can use qemu-guest-agent (domifaddr --source agent) after cloud-init starts qemu-ga.
    channels = [
      {
        source = {
          unix = {
            mode = "bind"
          }
        }
        target = {
          virt_io = {
            name = "org.qemu.guest_agent.0"
          }
        }
      }
    ]

    consoles = [
      {
        type        = "pty"
        target_port = "0"
        target_type = "serial"
      }
    ]
  }
}

output "id" {
  value = libvirt_domain.vm.id
}
