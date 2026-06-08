package itmo.backend.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import itmo.backend.config.InfraProperties;
import itmo.backend.model.dto.drift.VmConfigurationSnapshot;
import itmo.backend.model.entity.VirtualMachine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InfrastructureCommandService {

  private static final Logger log = LoggerFactory.getLogger(InfrastructureCommandService.class);
  private static final String GENERATED_TFVARS_RELATIVE_PATH = "tofu/generated/vms.auto.tfvars.json";
  private static final Pattern CLOUD_INIT_HOSTNAME_PATTERN = Pattern.compile("(?m)^hostname:\\s*(\\S+)\\s*$");
  private static final String ANSIBLE_INVENTORY_RELATIVE_PATH = "ansible/inventory/hosts.yml";
  private static final String WSL_ANSIBLE_PRIVATE_KEY_PATH = "/tmp/diploma_ansible_id_rsa";
  private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^([A-Za-z]):[\\\\/](.*)$");

  private final InfraProperties infraProperties;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public InfrastructureCommandService(final InfraProperties infraProperties) {
    this.infraProperties = infraProperties;
  }

  public boolean isEnabled() {
    return infraProperties.isEnabled();
  }

  public String getOsPlaybook(final String osImage) {
    return infraProperties.resolvePlaybook(osImage);
  }

  public String normalizeOsImage(final String osImage) {
    return infraProperties.normalizeOsImage(osImage);
  }

  public void writeDesiredState(final List<VirtualMachine> virtualMachines) throws IOException {
    log.info("Writing OpenTofu desired state for {} VM(s)", virtualMachines.size());
    final StringBuilder content = new StringBuilder();
    content.append("{\n");
    content.append("  \"vms\": {\n");

    for (int index = 0; index < virtualMachines.size(); index++) {
      final VirtualMachine vm = virtualMachines.get(index);
      final String osImage = infraProperties.normalizeOsImage(vm.getOsImage());
      content.append("    ")
        .append(quote(vm.getName()))
        .append(": {\n")
        .append("      \"name\": ").append(quote(vm.getName())).append(",\n")
        .append("      \"hostname\": ").append(quote(vm.getHostname())).append(",\n")
        .append("      \"vcpu\": ").append(vm.getVcpu()).append(",\n")
        .append("      \"memory_mb\": ").append(vm.getMemoryMb()).append(",\n")
        .append("      \"disk_size_gb\": ").append(vm.getDiskSizeGb()).append(",\n")
        .append("      \"os_image\": ").append(quote(osImage)).append(",\n")
        .append("      \"base_image_path\": ").append(quote(infraProperties.resolveBaseImagePath(osImage))).append("\n")
        .append("    }");

      if (index < virtualMachines.size() - 1) {
        content.append(",");
      }

      content.append("\n");
    }

    content.append("  }\n");
    content.append("}\n");

    final Path tfvarsPath = repoRoot().resolve(GENERATED_TFVARS_RELATIVE_PATH);
    Files.createDirectories(tfvarsPath.getParent());
    Files.writeString(tfvarsPath, content.toString(), StandardCharsets.UTF_8);
    log.info("OpenTofu desired state written to {}", tfvarsPath);
  }

  public void applyDesiredState() throws IOException, InterruptedException {
    log.info("Running OpenTofu apply for desired state");
    runInRepo("opentofu-apply", """
      set -euo pipefail
      %s
      export TF_CLI_CONFIG_FILE='%s/tofu/tofu.rc'
      cd '%s/tofu'
      %s init -input=false
      %s apply -auto-approve -input=false -parallelism=1 -var-file='generated/vms.auto.tfvars.json'
      """.formatted(
      openTofuEnvironmentPrologue(),
      repoRootForShell(),
      repoRootForShell(),
      infraProperties.getTofuCommand(),
      infraProperties.getTofuCommand()));
  }

  public void destroyDesiredState() throws IOException, InterruptedException {
    log.info("Reconciling infrastructure after VM removal");
    runInRepo("opentofu-reconcile", """
      set -euo pipefail
      %s
      export TF_CLI_CONFIG_FILE='%s/tofu/tofu.rc'
      cd '%s/tofu'
      %s init -input=false
      %s apply -auto-approve -input=false -parallelism=1 -var-file='generated/vms.auto.tfvars.json'
      """.formatted(
      openTofuEnvironmentPrologue(),
      repoRootForShell(),
      repoRootForShell(),
      infraProperties.getTofuCommand(),
      infraProperties.getTofuCommand()));
  }

  public String waitForVmIp(final String vmName) throws IOException, InterruptedException {
    log.info("Waiting for IP address of VM {}", vmName);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(infraProperties.getIpWaitTimeoutSeconds()));

    while (Instant.now().isBefore(deadline)) {
      final String output = runAndCapture("get-vm-ip-" + vmName, """
        cd '%s'
        bash ./scripts/get-vm-ip.sh '%s'
        """.formatted(repoRootForShell(), shellEscape(vmName)));

      final String ipAddress = output.trim();
      if (!ipAddress.isBlank() && !"pending".equalsIgnoreCase(ipAddress)) {
        log.info("IP address for VM {} resolved to {}", vmName, ipAddress);
        return ipAddress;
      }

      log.info("IP address for VM {} is still pending, retrying in {} second(s)",
        vmName, infraProperties.getIpPollIntervalSeconds());
      Thread.sleep(Duration.ofSeconds(infraProperties.getIpPollIntervalSeconds()).toMillis());
    }

    throw new IOException("Timed out while waiting for IP address of VM " + vmName);
  }

  public void writeAnsibleInventory(final List<VirtualMachine> virtualMachines, final Map<String, String> vmIps) throws IOException {
    log.info("Writing Ansible inventory for {} VM(s)", virtualMachines.size());
    final String ansiblePrivateKeyPath = toAnsiblePrivateKeyPathForExecution();
    final StringBuilder content = new StringBuilder();
    content.append("all:\n");
    content.append("  children:\n");
    content.append("    managed_vms:\n");
    content.append("      hosts:\n");

    for (final VirtualMachine virtualMachine : virtualMachines) {
      final String ipAddress = vmIps.get(virtualMachine.getName());
      if (ipAddress == null || ipAddress.isBlank()) {
        continue;
      }

      content.append("        ").append(virtualMachine.getName()).append(":\n");
      content.append("          ansible_host: ").append(ipAddress).append("\n");
      content.append("          ansible_user: ").append(infraProperties.resolveSshUser(virtualMachine.getOsImage())).append("\n");
      content.append("          ansible_python_interpreter: ").append(infraProperties.resolvePythonInterpreter(virtualMachine.getOsImage())).append("\n");
      content.append("          ansible_ssh_private_key_file: ")
        .append(ansiblePrivateKeyPath).append("\n");
      content.append("          ansible_ssh_common_args: '")
        .append("-o ProxyJump=root@").append(infraProperties.getVirtualizationHost())
        .append(" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null")
        .append("'\n");
      content.append("          environment_packages: ").append(toYamlInlineList(virtualMachine.getEnvironmentPackages())).append("\n");
    }

    final Path inventoryPath = repoRoot().resolve(ANSIBLE_INVENTORY_RELATIVE_PATH);
    Files.createDirectories(inventoryPath.getParent());
    Files.writeString(inventoryPath, content.toString(), StandardCharsets.UTF_8);
    log.info("Ansible inventory written to {}", inventoryPath);
  }

  public void runAnsibleForVm(final String vmName) throws IOException, InterruptedException {
    log.info("Running Ansible base playbook for VM {}", vmName);
    final String ansibleDir = repoRootForShell() + "/ansible";
    final String privateKeySourcePath = windowsPathToWsl(infraProperties.getAnsiblePrivateKeyPath());
    final String privateKeyRuntimePath = toAnsiblePrivateKeyPathForExecution();
    final String prepareKeyCommand = buildWslAnsibleKeyPrepareCommand(privateKeySourcePath, privateKeyRuntimePath);
    final String playbookFile = resolvePlaybookForVm(vmName);
    runInRepo("ansible-" + vmName, """
        %s
        export ANSIBLE_CONFIG='%s/ansible.cfg'
        %s \
            -i '%s/inventory/hosts.yml' \
            '%s/playbooks/%s' \
            -l '%s'
        """.formatted(
      prepareKeyCommand,
      ansibleDir,
      infraProperties.getAnsiblePlaybookCommand(),
      ansibleDir,
      ansibleDir,
      playbookFile,
      shellEscape(vmName)
    ));
  }

  public void runAnsibleForVmWithPlaybook(final String vmName, final String playbookFile)
    throws IOException, InterruptedException {
    log.info("Running Ansible playbook {} for VM {}", playbookFile, vmName);
    final String ansibleDir = repoRootForShell() + "/ansible";
    final String privateKeySourcePath = windowsPathToWsl(infraProperties.getAnsiblePrivateKeyPath());
    final String privateKeyRuntimePath = toAnsiblePrivateKeyPathForExecution();
    final String prepareKeyCommand = buildWslAnsibleKeyPrepareCommand(privateKeySourcePath, privateKeyRuntimePath);
    runInRepo("ansible-" + vmName + "-" + playbookFile, """
        %s
        export ANSIBLE_CONFIG='%s/ansible.cfg'
        %s \
            -i '%s/inventory/hosts.yml' \
            '%s/playbooks/%s' \
            -l '%s'
        """.formatted(
      prepareKeyCommand,
      ansibleDir,
      infraProperties.getAnsiblePlaybookCommand(),
      ansibleDir,
      ansibleDir,
      playbookFile,
      shellEscape(vmName)
    ));
  }

  private String resolvePlaybookForVm(final String vmName) {
    return "base.yml";
  }

  public void startVm(final String vmName) throws IOException, InterruptedException {
    log.info("Starting VM {} through libvirt", vmName);
    try {
      runInRepo("virsh-start-" + vmName,
        "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh start %s"
          .formatted(infraProperties.getVirtualizationHost(), shellEscape(vmName)));
    } catch (final IOException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("already active")) {
        log.info("VM {} is already active, continuing", vmName);
        return;
      }
      throw exception;
    }
  }

  public void stopVm(final String vmName) throws IOException, InterruptedException {
    log.info("Stopping VM {} through libvirt", vmName);
    runInRepo("virsh-stop-" + vmName,
      "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh shutdown %s"
        .formatted(infraProperties.getVirtualizationHost(), shellEscape(vmName)));
  }

  public String resolveVmPowerState(final String vmName) throws IOException, InterruptedException {
    log.info("Reading actual power state for VM {} through libvirt", vmName);
    final String output = runAndCapture("virsh-domstate-" + vmName,
      "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh domstate %s"
        .formatted(infraProperties.getVirtualizationHost(), shellEscape(vmName)));
    return output == null ? "" : output.trim();
  }

  public VmConfigurationSnapshot resolveLibvirtVmConfiguration(final String vmName) {
    if (!isEnabled()) {
      return VmConfigurationSnapshot.notFound();
    }

    try {
      final String output = runOnVirtualizationHostAndCapture(
        "libvirt-config-" + vmName,
        libvirtConfigurationScript(vmName)
      );
      final VmConfigurationSnapshot snapshot = parseConfigurationSnapshot(output);
      if (snapshot.found()) {
        log.info(
          "Libvirt state read for VM {}: vcpu={}, memoryMb={}, diskSizeGb={}, status={}",
          vmName,
          snapshot.vcpu(),
          snapshot.memoryMb(),
          snapshot.diskSizeGb(),
          snapshot.status()
        );
      } else {
        log.warn("Libvirt domain not found or unreadable for VM {}", vmName);
      }
      return snapshot;
    } catch (final Exception exception) {
      log.warn("Failed to read libvirt configuration for VM {}: {}", vmName, exception.getMessage());
      return VmConfigurationSnapshot.notFound();
    }
  }

  private String libvirtConfigurationScript(final String vmName) {
    return """
      export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

      VM_NAME='%s'

      if ! virsh dominfo "${VM_NAME}" >/dev/null 2>&1; then
        echo '{"found":false}'
        exit 0
      fi

      dominfo="$(virsh dominfo "${VM_NAME}")"
      blklist="$(virsh domblklist "${VM_NAME}" --details)"
      vcpu="$(awk -F: '/CPU\\(s\\)/ { gsub(/^[ \\t]+/, "", $2); print $2; exit }' <<< "${dominfo}")"
      mem_kib="$(awk '/Max memory/ { print $3; exit }' <<< "${dominfo}")"
      memory_mb=$((mem_kib / 1024))
      status="$(tr '[:upper:]' '[:lower:]' <<< "$(virsh domstate "${VM_NAME}")" | tr -d '\\r\\n')"
      disk_path="$(awk 'NR>2 && tolower($2)=="disk" && tolower($3)=="vda" {print $4; exit}' <<< "${blklist}")"

      disk_gb=0
      os_image="unknown"
      backing=""

      if [[ -n "${disk_path}" && -f "${disk_path}" ]]; then
        if command -v jq >/dev/null 2>&1; then
          img_json="$(qemu-img info --force-share --output=json "${disk_path}" 2>/dev/null || true)"
          if [[ -n "${img_json}" ]]; then
            virt_size="$(jq -r '."virtual-size" // 0' <<< "${img_json}")"
            backing="$(jq -r '."backing-filename" // empty' <<< "${img_json}")"
            if [[ "${virt_size}" =~ ^[0-9]+$ ]] && [[ "${virt_size}" -gt 0 ]]; then
              disk_gb=$(( (virt_size + 1073741823) / 1073741824 ))
            fi
          fi
        else
          img_info="$(qemu-img info --force-share "${disk_path}" 2>/dev/null || true)"
          if [[ -n "${img_info}" ]]; then
            backing="$(awk -F: '/backing file/ { gsub(/^[ \\t]+/, "", $2); print $2; exit }' <<< "${img_info}")"
            actual_line="$(awk -F: '/virtual size/ { print $2; exit }' <<< "${img_info}")"
            if [[ "${actual_line}" =~ \\(([0-9]+)\\ bytes\\) ]]; then
              bytes="${BASH_REMATCH[1]}"
              disk_gb=$(( (bytes + 1073741823) / 1073741824 ))
            fi
          fi
        fi
      fi

      case "${backing}" in
        *alpine*) os_image="alpine_3_19" ;;
        *ubuntu*) os_image="ubuntu_22_04" ;;
      esac

      if command -v jq >/dev/null 2>&1; then
        jq -n \
          --arg name "${VM_NAME}" \
          --arg hostname "${VM_NAME}" \
          --argjson vcpu "${vcpu:-0}" \
          --argjson memoryMb "${memory_mb:-0}" \
          --argjson diskSizeGb "${disk_gb:-0}" \
          --arg osImage "${os_image}" \
          --arg status "${status}" \
          '{found: true, name: $name, hostname: $hostname, vcpu: $vcpu, memoryMb: $memoryMb, diskSizeGb: $diskSizeGb, osImage: $osImage, environmentPackages: [], status: $status}'
      else
        printf '{"found":true,"name":"%%s","hostname":"%%s","vcpu":%%s,"memoryMb":%%s,"diskSizeGb":%%s,"osImage":"%%s","environmentPackages":[],"status":"%%s"}' \
          "${VM_NAME}" "${VM_NAME}" "${vcpu:-0}" "${memory_mb:-0}" "${disk_gb:-0}" "${os_image}" "${status}"
      fi
      """.formatted(shellEscape(vmName));
  }

  public VmConfigurationSnapshot resolveOpenTofuVmConfiguration(final String vmName) {
    if (!isEnabled()) {
      return VmConfigurationSnapshot.notFound();
    }

    final VmConfigurationSnapshot fromTfvars = resolveGeneratedTfvarsVmConfiguration(vmName);

    try {
      final String output = runAndCapture("opentofu-show-json-" + vmName, """
        set -euo pipefail
        %s
        export TF_CLI_CONFIG_FILE='%s/tofu/tofu.rc'
        cd '%s/tofu'
        %s init -input=false >/dev/null
        %s show -json
        """.formatted(
        openTofuEnvironmentPrologue(),
        repoRootForShell(),
        repoRootForShell(),
        infraProperties.getTofuCommand(),
        infraProperties.getTofuCommand()
      ));
      final VmConfigurationSnapshot fromState = parseOpenTofuShowJson(output, vmName);
      if (fromState.found()) {
        return fromState;
      }
    } catch (final Exception exception) {
      log.warn("Failed to read OpenTofu state for VM {}: {}", vmName, exception.getMessage());
    }

    return fromTfvars.found() ? fromTfvars : VmConfigurationSnapshot.notFound();
  }

  public VmConfigurationSnapshot resolveGuestVmProfile(
    final String vmName,
    final String osImage,
    final String ipAddress
  ) {
    if (!isEnabled()) {
      return VmConfigurationSnapshot.notFound();
    }

    final String resolvedIp = resolveGuestIp(vmName, ipAddress);
    if (resolvedIp == null || resolvedIp.isBlank() || "pending".equalsIgnoreCase(resolvedIp)) {
      log.warn("Guest profile for VM {} skipped: IP address is unavailable", vmName);
      return VmConfigurationSnapshot.notFound();
    }

    try {
      ensureGuestSshKeyOnHypervisor();
      final String sshUser = infraProperties.resolveSshUser(osImage);
      log.info("Reading guest profile for VM {} via {}@{}", vmName, sshUser, resolvedIp);
      final String content = runAndCapture(
        "guest-profile-" + vmName,
        """
          ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- \
            ssh -i /tmp/id_rsa_vm_pem -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null %s@%s cat /etc/diploma-vm-info
          """.formatted(
          infraProperties.getVirtualizationHost(),
          shellEscape(sshUser),
          shellEscape(resolvedIp)
        )
      );
      final VmConfigurationSnapshot snapshot = parseDiplomaVmInfo(content);
      if (snapshot.found()) {
        log.info(
          "Guest profile read for VM {}: hostname={}, environmentPackages={}",
          vmName,
          snapshot.hostname(),
          snapshot.environmentPackages()
        );
      } else {
        log.warn("Guest profile not found for VM {} at {} (raw response: {})", vmName, resolvedIp, summarize(content));
      }
      return snapshot;
    } catch (final Exception exception) {
      log.warn("Failed to read guest profile for VM {}: {}", vmName, exception.getMessage());
      return VmConfigurationSnapshot.notFound();
    }
  }

  private VmConfigurationSnapshot parseDiplomaVmInfo(final String content) {
    if (content == null || content.isBlank()) {
      return VmConfigurationSnapshot.notFound();
    }

    String hostname = null;
    final List<String> packages = new ArrayList<>();

    for (final String rawLine : content.split("\\R")) {
      final String line = rawLine == null ? "" : rawLine.replace("\uFEFF", "").trim();
      if (line.startsWith("hostname=")) {
        hostname = line.substring("hostname=".length()).trim();
      }
      if (line.startsWith("environment_packages=")) {
        final String rawPackages = line.substring("environment_packages=".length()).trim();
        if (!rawPackages.isBlank()) {
          for (final String value : rawPackages.split(",")) {
            final String packageName = value.trim();
            if (!packageName.isBlank()) {
              packages.add(packageName);
            }
          }
        }
      }
    }

    if (hostname == null && packages.isEmpty()) {
      return VmConfigurationSnapshot.notFound();
    }

    final List<String> sortedPackages = packages.stream().sorted().toList();
    return new VmConfigurationSnapshot(
      true,
      null,
      hostname,
      null,
      null,
      null,
      null,
      sortedPackages,
      null
    );
  }

  private void ensureGuestSshKeyOnHypervisor() {
    try {
      final String keySourcePath = windowsPathToWsl(infraProperties.getAnsiblePrivateKeyPath());
      runInRepo("ensure-guest-ssh-key", """
        scp -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=no '%s' root@%s:/tmp/id_rsa_vm_pem
        ssh -o BatchMode=yes -o ConnectTimeout=10 root@%s -- chmod 600 /tmp/id_rsa_vm_pem
        """.formatted(
        shellEscape(keySourcePath),
        infraProperties.getVirtualizationHost(),
        infraProperties.getVirtualizationHost()
      ));
    } catch (final Exception exception) {
      log.warn("Could not install guest SSH key on virtualization host: {}", exception.getMessage());
    }
  }

  private String resolveGuestIp(final String vmName, final String ipAddress) {
    try {
      final String output = runAndCapture("get-vm-ip-" + vmName, """
        cd '%s'
        sed 's/\\r$//' ./scripts/get-vm-ip.sh | bash -s -- '%s' '%s'
        """.formatted(
        repoRootForShell(),
        shellEscape(vmName),
        shellEscape(infraProperties.getVirtualizationHost())
      ));
      final String resolved = output == null ? "" : output.trim();
      if (!resolved.isBlank() && !"pending".equalsIgnoreCase(resolved)) {
        return resolved;
      }
    } catch (final Exception exception) {
      log.warn("Failed to resolve guest IP for VM {} via libvirt: {}", vmName, exception.getMessage());
    }

    if (ipAddress != null && !ipAddress.isBlank() && !"pending".equalsIgnoreCase(ipAddress)) {
      return ipAddress;
    }

    return null;
  }

  private VmConfigurationSnapshot resolveGeneratedTfvarsVmConfiguration(final String vmName) {
    try {
      final Path tfvarsPath = repoRoot().resolve(GENERATED_TFVARS_RELATIVE_PATH);
      if (!Files.exists(tfvarsPath)) {
        return VmConfigurationSnapshot.notFound();
      }

      final JsonNode root = objectMapper.readTree(Files.readString(tfvarsPath, StandardCharsets.UTF_8));
      final JsonNode vmNode = root.path("vms").path(vmName);
      if (vmNode.isMissingNode() || vmNode.isNull()) {
        return VmConfigurationSnapshot.notFound();
      }

      return new VmConfigurationSnapshot(
        true,
        textValue(vmNode, "name", vmName),
        textValue(vmNode, "hostname", vmName),
        intValue(vmNode, "vcpu"),
        intValue(vmNode, "memory_mb"),
        intValue(vmNode, "disk_size_gb"),
        infraProperties.normalizeOsImage(textValue(vmNode, "os_image", "unknown")),
        List.of(),
        null
      );
    } catch (final Exception exception) {
      log.warn("Failed to read generated tfvars for VM {}: {}", vmName, exception.getMessage());
      return VmConfigurationSnapshot.notFound();
    }
  }

  private VmConfigurationSnapshot parseOpenTofuShowJson(final String output, final String vmName) {
    try {
      final JsonNode rootModule = objectMapper.readTree(output).path("values").path("root_module");
      final String modulePrefix = "module.vms[\"" + vmName + "\"].";

      final JsonNode domainValues = findResourceValuesByAddress(rootModule, modulePrefix + "libvirt_domain.vm");
      final JsonNode diskValues = findResourceValuesByAddress(rootModule, modulePrefix + "libvirt_volume.disk");
      final JsonNode cloudInitValues = findResourceValuesByAddress(rootModule, modulePrefix + "libvirt_cloudinit_disk.init");

      if (domainValues.isMissingNode()) {
        return VmConfigurationSnapshot.notFound();
      }

      final String hostname = parseHostnameFromUserData(textValue(cloudInitValues, "user_data", null));
      final String backingPath = resolveBackingStorePath(diskValues);
      final long diskCapacity = longValue(diskValues, "capacity");

      log.info(
        "OpenTofu state parsed for VM {}: vcpu={}, memoryMb={}, diskSizeGb={}, osImage={}",
        vmName,
        intValue(domainValues, "vcpu"),
        memoryMbFromLibvirtKiB(longValue(domainValues, "memory")),
        diskGbFromBytes(diskCapacity),
        inferOsImageFromBackingPath(backingPath)
      );

      return new VmConfigurationSnapshot(
        true,
        textValue(domainValues, "name", vmName),
        hostname == null ? textValue(domainValues, "name", vmName) : hostname,
        intValue(domainValues, "vcpu"),
        memoryMbFromLibvirtKiB(longValue(domainValues, "memory")),
        diskGbFromBytes(diskCapacity),
        inferOsImageFromBackingPath(backingPath),
        List.of(),
        null
      );
    } catch (final Exception exception) {
      log.warn("Failed to parse OpenTofu show JSON for VM {}: {}", vmName, exception.getMessage());
      return VmConfigurationSnapshot.notFound();
    }
  }

  private VmConfigurationSnapshot parseConfigurationSnapshot(final String output) {
    try {
      final JsonNode root = objectMapper.readTree(output);
      if (!root.path("found").asBoolean(false)) {
        return VmConfigurationSnapshot.notFound();
      }

      return new VmConfigurationSnapshot(
        true,
        nullableText(root, "name"),
        nullableText(root, "hostname"),
        nullableInt(root, "vcpu"),
        nullableInt(root, "memoryMb"),
        nullableInt(root, "diskSizeGb"),
        nullableText(root, "osImage"),
        readStringList(root.path("environmentPackages")),
        nullableText(root, "status")
      );
    } catch (final Exception exception) {
      log.warn("Failed to parse VM configuration snapshot JSON: {}", exception.getMessage());
      return VmConfigurationSnapshot.notFound();
    }
  }

  private JsonNode findResourceValuesByAddress(final JsonNode module, final String targetAddress) {
    for (final JsonNode resource : module.path("resources")) {
      if (targetAddress.equals(resource.path("address").asText())) {
        return resource.path("values");
      }
    }

    for (final JsonNode child : module.path("child_modules")) {
      final JsonNode found = findResourceValuesByAddress(child, targetAddress);
      if (!found.isMissingNode()) {
        return found;
      }
    }

    return objectMapper.missingNode();
  }

  private String resolveBackingStorePath(final JsonNode diskValues) {
    if (diskValues == null || diskValues.isMissingNode()) {
      return null;
    }

    final String directPath = textValue(diskValues.path("backing_store"), "path", null);
    if (directPath != null && !directPath.isBlank()) {
      return directPath;
    }

    return textValue(diskValues, "backing_store", null);
  }

  private String parseHostnameFromUserData(final String userData) {
    if (userData == null || userData.isBlank()) {
      return null;
    }

    final Matcher matcher = CLOUD_INIT_HOSTNAME_PATTERN.matcher(userData);
    return matcher.find() ? matcher.group(1) : null;
  }

  private String inferOsImageFromBackingPath(final String backingPath) {
    if (backingPath == null || backingPath.isBlank()) {
      return "unknown";
    }

    final String lower = backingPath.toLowerCase(Locale.ROOT);
    if (lower.contains("alpine")) {
      return "alpine_3_19";
    }
    if (lower.contains("ubuntu")) {
      return "ubuntu_22_04";
    }

    return "unknown";
  }

  private int memoryMbFromLibvirtKiB(final long memoryKiB) {
    if (memoryKiB <= 0) {
      return 0;
    }
    return (int) (memoryKiB / 1024L);
  }

  private int diskGbFromBytes(final long capacityBytes) {
    if (capacityBytes <= 0) {
      return 0;
    }
    return (int) ((capacityBytes + 1_073_741_823L) / 1_073_741_824L);
  }

  private List<String> readStringList(final JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }

    return StreamSupport.stream(node.spliterator(), false)
      .map(JsonNode::asText)
      .filter(value -> value != null && !value.isBlank())
      .sorted()
      .toList();
  }

  private String textValue(final JsonNode node, final String field, final String fallback) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return fallback;
    }

    final JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
  }

  private String nullableText(final JsonNode node, final String field) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }

    final JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  private Integer nullableInt(final JsonNode node, final String field) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }

    final JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asInt();
  }

  private int intValue(final JsonNode node, final String field) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return 0;
    }

    return node.path(field).asInt(0);
  }

  private long longValue(final JsonNode node, final String field) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return 0L;
    }

    return node.path(field).asLong(0L);
  }

  public void createExternalDiskSnapshot(final String vmName, final String snapshotName)
    throws IOException, InterruptedException {
    log.info("Creating external disk-only snapshot {} for VM {}", snapshotName, vmName);
    final String sourcePath = resolveVmDiskSourcePath(vmName, "vda");
    final String snapshotPath = buildSnapshotPath(sourcePath, snapshotName);
    runInRepo("virsh-snapshot-create-" + vmName + "-" + snapshotName,
      "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh snapshot-create-as %s %s --disk-only --halt --atomic --diskspec vda,snapshot=external,file=%s"
        .formatted(
          infraProperties.getVirtualizationHost(),
          shellEscape(vmName),
          shellEscape(snapshotName),
          shellEscape(snapshotPath)
        ));
  }

  public void restoreExternalDiskSnapshot(final String vmName, final String snapshotName)
    throws IOException, InterruptedException {
    log.info("Restoring VM {} from external disk-only snapshot {}", vmName, snapshotName);
    runInRepo("snapshot-restore-" + vmName + "-" + snapshotName, """
      cd '%s'
      bash ./scripts/restore-snapshot.sh '%s' '%s' '%s'
      """.formatted(
      repoRootForShell(),
      shellEscape(vmName),
      shellEscape(snapshotName),
      shellEscape(infraProperties.getVirtualizationHost())
    ));
  }

  public void deleteSnapshotMetadata(final String vmName, final String snapshotName)
    throws IOException, InterruptedException {
    log.info("Deleting libvirt snapshot metadata {} for VM {}", snapshotName, vmName);
    runInRepo("virsh-snapshot-delete-" + vmName + "-" + snapshotName,
      "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh snapshot-delete %s %s --metadata"
        .formatted(
          infraProperties.getVirtualizationHost(),
          shellEscape(vmName),
          shellEscape(snapshotName)
        ));
  }

  public String runOnVirtualizationHostAndCapture(final String commandName, final String remoteCommand)
    throws IOException, InterruptedException {
    final String sshCommand = "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 %s@%s -- bash -s"
      .formatted(
        shellEscape(infraProperties.getVirtualizationUser()),
        infraProperties.getVirtualizationHost()
      );
    log.info("monitoring/remote script length={} bytes", remoteCommand.getBytes(StandardCharsets.UTF_8).length);
    return runAndCaptureWithStdin(commandName, sshCommand, remoteCommand);
  }

  public void runOnVirtualizationHost(final String commandName, final String remoteCommand)
    throws IOException, InterruptedException {
    final String sshCommand = "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 %s@%s -- bash -s"
      .formatted(
        shellEscape(infraProperties.getVirtualizationUser()),
        infraProperties.getVirtualizationHost()
      );
    log.info("infra/remote script length={} bytes", remoteCommand.getBytes(StandardCharsets.UTF_8).length);
    final CommandResult result = executeWithStdin(commandName, sshCommand, remoteCommand);
    if (result.exitCode() != 0) {
      log.error("Infrastructure command {} failed with exit code {}. stderr: {}",
        commandName, result.exitCode(), summarize(result.stderr()));
      throw new IOException(("Command failed with exit code " + result.exitCode()
        + "\n" + result.stdout() + "\n" + result.stderr()).trim());
    }
  }

  private String runAndCaptureWithStdin(final String commandName, final String command, final String stdinContent)
    throws IOException, InterruptedException {
    final CommandResult result = executeWithStdin(commandName, command, stdinContent);
    if (result.exitCode() != 0) {
      log.error("Infrastructure command {} with captured output failed with exit code {}. stderr: {}",
        commandName, result.exitCode(), summarize(result.stderr()));
      throw new IOException(("Command failed with exit code " + result.exitCode()
        + "\n" + result.stdout() + "\n" + result.stderr()).trim());
    }
    if (!result.stderr().isBlank()) {
      log.warn("Infrastructure command {} produced stderr (exit=0): {}", commandName, summarize(result.stderr()));
    }
    return result.stdout();
  }

  private CommandResult executeWithStdin(final String commandName, final String command, final String stdinContent)
    throws IOException, InterruptedException {
    log.info("Executing infrastructure command [{}] with stdin: {}", commandName, summarize(command));
    final Process process = buildProcess(command).start();

    try (var os = process.getOutputStream()) {
      if (stdinContent != null) {
        os.write(stdinContent.getBytes(StandardCharsets.UTF_8));
      }
      os.flush();
    } catch (final IOException ioException) {
      log.warn("Failed to write stdin for command {}: {}", commandName, ioException.getMessage());
    }

    final StringBuilder stdout = new StringBuilder();
    final StringBuilder stderr = new StringBuilder();

    final Thread stdoutThread = startStreamLogger(process.getInputStream(), stdout, commandName, false);
    final Thread stderrThread = startStreamLogger(process.getErrorStream(), stderr, commandName, true);

    final long startedAt = System.nanoTime();
    final int progressInterval = Math.max(1, infraProperties.getCommandProgressLogIntervalSeconds());
    final int timeoutSeconds = Math.max(progressInterval, infraProperties.getCommandTimeoutSeconds());
    int elapsedSeconds = 0;

    while (!process.waitFor(progressInterval, TimeUnit.SECONDS)) {
      elapsedSeconds += progressInterval;
      log.info("Infrastructure command [{}] is still running after {} second(s)", commandName, elapsedSeconds);
      if (elapsedSeconds >= timeoutSeconds) {
        process.destroyForcibly();
        joinStreamThread(stdoutThread);
        joinStreamThread(stderrThread);
        throw new IOException("Infrastructure command timed out after " + timeoutSeconds + " seconds: " + commandName);
      }
    }

    final int exitCode = process.exitValue();
    joinStreamThread(stdoutThread);
    joinStreamThread(stderrThread);

    final long durationSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
    log.info("Infrastructure command [{}] finished in {} second(s) with exit code {}", commandName, durationSeconds, exitCode);
    return new CommandResult(exitCode, stdout.toString(), stderr.toString());
  }

  private static String openTofuEnvironmentPrologue() {
    return """
      export XDG_RUNTIME_DIR="$HOME/.cache/opentofu-xdg-runtime"
      mkdir -p "$XDG_RUNTIME_DIR"
      chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
      """.strip();
  }

  private void runInRepo(final String commandName, final String command) throws IOException, InterruptedException {
    final CommandResult result = execute(commandName, command);
    final int exitCode = result.exitCode();
    if (exitCode != 0) {
      log.error("Infrastructure command {} failed with exit code {}", commandName, exitCode);
      throw new IOException(("Command failed with exit code " + exitCode + "\n" + result.stdout() + "\n" + result.stderr()).trim());
    }

    if (!result.stdout().isBlank()) {
      log.info("Infrastructure command {} completed successfully: {}", commandName, summarize(result.stdout()));
    }
  }

  private String runAndCapture(final String commandName, final String command) throws IOException, InterruptedException {
    final CommandResult result = execute(commandName, command);
    if (result.exitCode() != 0) {
      log.error("Infrastructure command {} with captured output failed with exit code {}", commandName, result.exitCode());
      throw new IOException(("Command failed with exit code " + result.exitCode() + "\n" + result.stdout() + "\n" + result.stderr()).trim());
    }

    return result.stdout();
  }

  private ProcessBuilder buildProcess(final String command) {
    if (infraProperties.isUseWsl()) {
      return new ProcessBuilder("wsl.exe", "-e", "bash", "-lc", command);
    }

    return new ProcessBuilder("bash", "-lc", command)
      .directory(repoRoot().toFile());
  }

  private Path repoRoot() {
    return Path.of(infraProperties.getRepoRoot());
  }

  private String repoRootForShell() {
    return infraProperties.isUseWsl() ? infraProperties.getWslRepoRoot() : infraProperties.getRepoRoot();
  }

  private String quote(final String value) {
    if (value == null) {
      return "\"\"";
    }

    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private String shellEscape(final String value) {
    return value.replace("'", "'\"'\"'");
  }

  private String toYamlInlineList(final List<?> values) {
    if (values == null || values.isEmpty()) {
      return "[]";
    }

    final StringJoiner joiner = new StringJoiner(", ", "[", "]");
    for (final Object value : values) {
      joiner.add(String.valueOf(value));
    }
    return joiner.toString();
  }

  private String toAnsiblePrivateKeyPathForExecution() {
    final String configuredPath = infraProperties.getAnsiblePrivateKeyPath();
    if (!infraProperties.isUseWsl()) {
      return configuredPath;
    }

    final String wslPath = windowsPathToWsl(configuredPath);
    if (wslPath.startsWith("/mnt/")) {
      return WSL_ANSIBLE_PRIVATE_KEY_PATH;
    }

    return wslPath;
  }

  private String buildWslAnsibleKeyPrepareCommand(final String sourcePath, final String targetPath) {
    if (!infraProperties.isUseWsl()) {
      return "";
    }

    if (!sourcePath.startsWith("/mnt/") || sourcePath.equals(targetPath)) {
      return "";
    }

    return """
      install -m 600 '%s' '%s'
      """.formatted(shellEscape(sourcePath), shellEscape(targetPath));
  }

  private String windowsPathToWsl(final String path) {
    if (path == null || path.isBlank()) {
      return path;
    }

    final String normalized = path.replace('\\', '/');
    final Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(normalized);
    if (!matcher.matches()) {
      return normalized;
    }

    final String drive = matcher.group(1).toLowerCase(Locale.ROOT);
    final String rest = matcher.group(2);
    return "/mnt/" + drive + "/" + rest;
  }

  private String resolveVmDiskSourcePath(final String vmName, final String targetDevice)
    throws IOException, InterruptedException {
    final String output = runAndCapture("virsh-domblklist-" + vmName, "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh domblklist %s --details"
      .formatted(infraProperties.getVirtualizationHost(), shellEscape(vmName)));

    for (final String rawLine : output.split("\\R")) {
      final String line = rawLine.trim();
      if (line.isBlank() || line.startsWith("Type") || line.startsWith("---")) {
        continue;
      }

      final String[] parts = line.split("\\s+");
      if (parts.length >= 4 && "disk".equalsIgnoreCase(parts[1]) && targetDevice.equalsIgnoreCase(parts[2])) {
        return parts[3];
      }
    }

    throw new IOException("Could not resolve source disk path for VM " + vmName + " and device " + targetDevice);
  }

  private String buildSnapshotPath(final String sourcePath, final String snapshotName) {
    final String fileName = snapshotName + ".qcow2";
    final String normalizedSourcePath = sourcePath == null ? "" : sourcePath.trim();

    if (normalizedSourcePath.isBlank()) {
      return "/var/lib/libvirt/images/" + fileName;
    }

    final String absoluteSourcePath = normalizedSourcePath.startsWith("/")
      ? normalizedSourcePath
      : "/var/lib/libvirt/images/" + normalizedSourcePath;

    final int lastSlash = absoluteSourcePath.lastIndexOf('/');
    if (lastSlash < 0) {
      return "/var/lib/libvirt/images/" + fileName;
    }

    return absoluteSourcePath.substring(0, lastSlash + 1) + fileName;
  }

  private String summarize(final String text) {
    final String normalized = sanitizeText(text)
      .replace('\r', ' ')
      .replace('\n', ' ')
      .replaceAll("\\s+", " ")
      .trim();
    if (normalized.length() <= 300) {
      return normalized;
    }

    return normalized.substring(0, 297) + "...";
  }

  private CommandResult execute(final String commandName, final String command) throws IOException, InterruptedException {
    log.info("Executing infrastructure command [{}]: {}", commandName, summarize(command));
    final Process process = buildProcess(command).start();

    final StringBuilder stdout = new StringBuilder();
    final StringBuilder stderr = new StringBuilder();

    final Thread stdoutThread = startStreamLogger(process.getInputStream(), stdout, commandName, false);
    final Thread stderrThread = startStreamLogger(process.getErrorStream(), stderr, commandName, true);

    final long startedAt = System.nanoTime();
    final int progressInterval = Math.max(1, infraProperties.getCommandProgressLogIntervalSeconds());
    final int timeoutSeconds = Math.max(progressInterval, infraProperties.getCommandTimeoutSeconds());
    int elapsedSeconds = 0;

    while (!process.waitFor(progressInterval, TimeUnit.SECONDS)) {
      elapsedSeconds += progressInterval;
      log.info("Infrastructure command [{}] is still running after {} second(s)", commandName, elapsedSeconds);
      if (elapsedSeconds >= timeoutSeconds) {
        process.destroyForcibly();
        joinStreamThread(stdoutThread);
        joinStreamThread(stderrThread);
        throw new IOException("Infrastructure command timed out after " + timeoutSeconds + " seconds: " + commandName);
      }
    }

    final int exitCode = process.exitValue();
    joinStreamThread(stdoutThread);
    joinStreamThread(stderrThread);

    final long durationSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
    log.info("Infrastructure command [{}] finished in {} second(s) with exit code {}", commandName, durationSeconds, exitCode);
    return new CommandResult(exitCode, stdout.toString(), stderr.toString());
  }

  private Thread startStreamLogger(
    final InputStream inputStream,
    final StringBuilder collector,
    final String commandName,
    final boolean errorStream
  ) {
    final Thread thread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          final String sanitizedLine = sanitizeText(line);
          collector.append(sanitizedLine).append(System.lineSeparator());
          if (errorStream) {
            log.warn("infra[{}][stderr] {}", commandName, summarize(sanitizedLine));
          } else if (sanitizedLine.length() > 400) {
            log.info("infra[{}][stdout] <{} bytes>", commandName, sanitizedLine.length());
          } else {
            log.info("infra[{}][stdout] {}", commandName, sanitizedLine);
          }
        }
      } catch (final IOException exception) {
        log.warn("Failed to read process stream for command {}", commandName, exception);
      }
    }, "infra-" + commandName + (errorStream ? "-stderr" : "-stdout"));

    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void joinStreamThread(final Thread thread) throws InterruptedException {
    thread.join(TimeUnit.SECONDS.toMillis(2));
  }

  private record CommandResult(
    int exitCode,
    String stdout,
    String stderr
  ) {
  }

  private String sanitizeText(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }

    final String withoutNulls = text.replace("\u0000", "");
    return withoutNulls
      .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
      .replace('\uFEFF', ' ');
  }
}
