output "name" {
  description = "Имя ВМ"
  value       = libvirt_domain.vm.name
}

output "id" {
  description = "ID ВМ в libvirt"
  value       = libvirt_domain.vm.id
}

output "ip_address" {
  description = "IP-адрес ВМ"
  value       = libvirt_domain.vm.network_interface[0].addresses[0]
}

output "volume_id" {
  description = "ID диска (для создания снимков)"
  value       = libvirt_volume.disk.id
}
