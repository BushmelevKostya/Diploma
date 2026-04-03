terraform {
  required_version = ">= 1.6.0"

  required_providers {
    libvirt = {
      source  = "dmacvicar/libvirt"
      version = "0.9.7"
    }
  }

  backend "s3" {
    bucket = "tofu-state"
    key    = "workstation/terraform.tfstate"
    region = "us-east-1"

    endpoints = {
      s3 = "http://localhost:9000"
    }

    access_key                  = "minioadmin"
    secret_key                  = "minioadmin"
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    use_path_style              = true
  }
}

provider "libvirt" {
  uri = "qemu+ssh://root@144.31.219.63/system"
}

resource "libvirt_volume" "ubuntu" {
  name = "ubuntu-base.qcow2"
  pool = var.storage_pool_name

  create = {
    content = {
      url = var.base_image_url
    }
  }
}

module "vm1" {
  source          = "./modules/vm"
  name            = "vm1"
  vcpu            = 1
  memory          = 1024
  disk_size_gb    = 10
  base_image_path = libvirt_volume.ubuntu.path
  network_name    = "default"
  storage_pool    = var.storage_pool_name
  ssh_public_key  = file("${path.module}/id_rsa.pub")
}

variable "base_image_url" {
  type    = string
  default = "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img"
}

variable "storage_pool_name" {
  type    = string
  default = "default"
}
