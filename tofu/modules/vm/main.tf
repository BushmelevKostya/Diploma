terraform {
  required_providers {
    libvirt = {
      source  = "dmacvicar/libvirt"
      version = "0.9.7"
    }
  }
}

resource "libvirt_cloudinit_disk" "init" {
  name = "${var.name}-cloudinit.iso"

  user_data = <<-EOF
    #cloud-config
    hostname: ${var.name}
    ssh_authorized_keys:
      - ${var.ssh_public_key}
    growpart:
      mode: auto
    resize_rootfs: true
  EOF

  network_config = <<-EOF
    version: 2
    ethernets:
      ens3:
        dhcp4: true
  EOF

  meta_data = <<-EOF
    instance-id: ${var.name}
    local-hostname: ${var.name}
  EOF
}

resource "libvirt_volume" "cloudinit" {
  name = "${var.name}-cloudinit.iso"
  pool = var.storage_pool

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

    consoles = [
      {
        type        = "pty"
        target_port = "0"
        target_type = "serial"
      }
    ]
  }
}
