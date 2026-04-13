variable "name" {
  type = string
}
variable "hostname" {
  type = string
}
variable "vcpu" {
  type = number
}
variable "memory" {
  type = number
}
variable "disk_size_gb" {
  type = number
}
variable "base_image_path" {
  type = string
}
variable "network_name" {
  type = string
}
variable "storage_pool" {
  type = string
}
variable "ssh_public_key" {
  type = string
}
