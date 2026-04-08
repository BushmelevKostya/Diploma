output "vm_name" {
  value = libvirt_domain.vm.name
}

output "ip" {
  value       = "pending"
  description = "IPv4 address - will be populated after ВМ gets DHCP lease. Use get-vm-ip.sh to fetch."
}
