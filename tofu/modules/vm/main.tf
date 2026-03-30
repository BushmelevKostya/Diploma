resource "libvirt_volume" "disk" {
  name           = "${var.name}-disk.qcow2"
  pool           = var.storage_pool
  base_volume_id = var.base_image_id
  format         = "qcow2"

  # Размер в байтах
  size = var.disk_size_gb * 1024 * 1024 * 1024
}

resource "libvirt_cloudinit_disk" "init" {
  name = "${var.name}-cloudinit.iso"
  pool = var.storage_pool

  user_data = var.user_data != "" ? var.user_data : templatefile(
    "${path.module}/templates/cloud-init.yml.tftpl",
    {
      hostname       = var.name
      ssh_public_key = var.ssh_public_key
    }
  )
}

resource "libvirt_domain" "vm" {
  name   = var.name
  memory = var.memory
  vcpu   = var.vcpu

  cloudinit = libvirt_cloudinit_disk.init.id

  disk {
    volume_id = libvirt_volume.disk.id
  }

  network_interface {
    network_id     = var.network_id
    wait_for_lease = true
  }

  console {
    type        = "pty"
    target_port = "0"
    target_type = "serial"
  }

  graphics {
    type        = "vnc"
    listen_type = "address"
    autoport    = true
  }
}
