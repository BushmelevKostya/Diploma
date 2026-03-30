# ═══════════════════════════════════════
# Глобальные переменные
# ═══════════════════════════════════════

variable "base_image_url" {
  description = "URL или локальный путь к базовому образу ОС (qcow2)"
  type        = string
  default     = "https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-amd64.img"
}

variable "storage_pool_name" {
  description = "Имя libvirt storage pool для хранения дисков ВМ"
  type        = string
  default     = "default"
}

variable "network_name" {
  description = "Имя libvirt network"
  type        = string
  default     = "default"
}
