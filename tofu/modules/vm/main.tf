terraform {
  required_providers {
    libvirt = {
      source  = "dmacvicar/libvirt"
      version = "0.9.7"
    }
  }
}

# Cloud-init ISO
resource "libvirt_cloudinit_disk" "init" {
  name = "${var.name}-cloudinit.iso"

  user_data = templatefile("${path.module}/templates/cloud-init.yml.tftpl", {
    hostname       = var.name
    ssh_public_key = var.ssh_public_key
  })

  meta_data = yamlencode({
    "instance-id"    = var.name
    "local-hostname" = var.name
  })
}

# Upload cloud-init ISO to pool
resource "libvirt_volume" "cloudinit" {
  name = "${var.name}-cloudinit.iso"
  pool = var.storage_pool

  create = {
    content = {
      url = libvirt_cloudinit_disk.init.path
    }
  }
}

# COW disk
resource "libvirt_volume" "disk" {
  name          = "${var.name}-disk.qcow2"
  pool          = var.storage_pool
  capacity      = var.disk_size_gb * 1024 * 1024 * 1024
  capacity_unit = "bytes"

  backing_store = {
    path = var.base_image_path
    format = {
      type = "qcow2"
    }
  }

  target = {
    format = {
      type = "qcow2"
    }
  }
}

# VM
resource "libvirt_domain" "vm" {
  name    = var.name
  type    = "kvm"
  memory  = var.memory
  vcpu    = var.vcpu
  running = true

  os = {
    type      = "hvm"
    type_arch = "x86_64"
  }

  devices = {
    disks = [
      {
        source = {
          file = {
            file = libvirt_volume.disk.path
          }
        }
        target = {
          dev = "vda"
          bus = "virtio"
        }
        driver = {
          name = "qemu"
          type = "qcow2"
        }
      },
      {
        device = "cdrom"
        source = {
          file = {
            file = libvirt_volume.cloudinit.path
          }
        }
        target = {
          dev = "sda"
          bus = "sata"
        }
        driver = {
          name = "qemu"
          type = "raw"
        }
      }
    ]

    interfaces = [
      {
        source = {
          network = {
            network = var.network_name
          }
        }
        model = {
          type = "virtio"
        }
      }
    ]

    consoles = [
      {
        target = {
          type = "serial"
          port = 0
        }
      }
    ]

    graphics = [
      {
        vnc = {
          auto_port = true
          listen    = "0.0.0.0"
        }
      }
    ]
  }
}
