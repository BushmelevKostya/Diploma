variable "name" {
  description = "Имя виртуальной машины"
  type        = string
}

variable "vcpu" {
  description = "Количество виртуальных CPU"
  type        = number
  default     = 2
}

variable "memory" {
  description = "Объём оперативной памяти (МБ)"
  type        = number
  default     = 2048
}

variable "disk_size_gb" {
  description = "Размер диска (ГБ)"
  type        = number
  default     = 20
}

variable "base_image_id" {
  description = "ID базового образа (libvirt_volume)"
  type        = string
}

variable "network_id" {
  description = "ID сети libvirt"
  type        = string
}

variable "storage_pool" {
  description = "Имя storage pool"
  type        = string
  default     = "default"
}

variable "user_data" {
  description = "Cloud-init user-data (YAML-строка)"
  type        = string
  default     = ""
}

variable "ssh_public_key" {
  description = "Публичный SSH-ключ для доступа к ВМ"
  type        = string
}
