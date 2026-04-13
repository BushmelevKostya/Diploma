terraform {
  required_version = ">= 1.6.0"

  required_providers {
    libvirt = {
      source  = "dmacvicar/libvirt"
      version = "0.9.7"
    }
  }

  backend "s3" {
    bucket                      = "tofu-state"
    key                         = "workstation/terraform.tfstate"
    region                      = "us-east-1"
    endpoints                   = { s3 = "http://localhost:9000" }
    access_key                  = "minioadmin"
    secret_key                  = "minioadmin"
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    use_path_style              = true
  }
}

variable "base_image_path" {
  type        = string
  default     = "/var/lib/libvirt/images/ubuntu-base.qcow2"
  description = "Path to pre-downloaded Ubuntu cloud image on the libvirt host"
}

variable "storage_pool_name" {
  type    = string
  default = "default"
}

variable "vms" {
  type = map(object({
    name         = string
    hostname     = string
    vcpu         = number
    memory_mb    = number
    disk_size_gb = number
    os_image     = string
  }))
  default = {}
}

provider "libvirt" {
  uri = "qemu+ssh://root@89.104.68.81/system"
}

# Base image is pre-downloaded to host once to avoid slow download during apply.
# The host copy lives at /var/lib/libvirt/images/ubuntu-base.qcow2.

module "vms" {
  for_each        = var.vms
  source          = "./modules/vm"
  name            = each.value.name
  hostname        = each.value.hostname
  vcpu            = each.value.vcpu
  memory          = each.value.memory_mb * 1024
  disk_size_gb    = each.value.disk_size_gb
  base_image_path = var.base_image_path
  network_name    = "default"
  storage_pool    = var.storage_pool_name
  ssh_public_key  = file("${path.module}/id_rsa.pub")
}

output "vm_ips" {
  value = { for name, vm in module.vms : name => vm.ip }
}
