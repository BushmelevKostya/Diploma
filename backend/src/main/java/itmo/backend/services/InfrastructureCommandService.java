package itmo.backend.services;

import itmo.backend.config.InfraProperties;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InfrastructureCommandService {

  private static final Logger log = LoggerFactory.getLogger(InfrastructureCommandService.class);
  private static final String GENERATED_TFVARS_RELATIVE_PATH = "tofu/generated/vms.auto.tfvars.json";
  private static final String ANSIBLE_INVENTORY_RELATIVE_PATH = "ansible/inventory/hosts.yml";

  private final InfraProperties infraProperties;

  public InfrastructureCommandService(final InfraProperties infraProperties) {
    this.infraProperties = infraProperties;
  }

  public boolean isEnabled() {
    return infraProperties.isEnabled();
  }

  public void writeDesiredState(final List<VirtualMachine> virtualMachines) throws IOException {
    log.info("Writing OpenTofu desired state for {} VM(s)", virtualMachines.size());
    final StringBuilder content = new StringBuilder();
    content.append("{\n");
    content.append("  \"vms\": {\n");

    for (int index = 0; index < virtualMachines.size(); index++) {
      final VirtualMachine vm = virtualMachines.get(index);
      content.append("    ")
        .append(quote(vm.getName()))
        .append(": {\n")
        .append("      \"name\": ").append(quote(vm.getName())).append(",\n")
        .append("      \"hostname\": ").append(quote(vm.getHostname())).append(",\n")
        .append("      \"vcpu\": ").append(vm.getVcpu()).append(",\n")
        .append("      \"memory_mb\": ").append(vm.getMemoryMb()).append(",\n")
        .append("      \"disk_size_gb\": ").append(vm.getDiskSizeGb()).append(",\n")
        .append("      \"os_image\": ").append(quote(vm.getOsImage())).append("\n")
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
      cd '%s/tofu'
      %s init -input=false
      %s apply -auto-approve -input=false -var-file='generated/vms.auto.tfvars.json'
      """.formatted(repoRootForShell(), infraProperties.getTofuCommand(), infraProperties.getTofuCommand()));
  }

  public void destroyDesiredState() throws IOException, InterruptedException {
    log.info("Reconciling infrastructure after VM removal");
    runInRepo("opentofu-reconcile", """
      cd '%s/tofu'
      %s init -input=false
      %s apply -auto-approve -input=false -var-file='generated/vms.auto.tfvars.json'
      """.formatted(repoRootForShell(), infraProperties.getTofuCommand(), infraProperties.getTofuCommand()));
  }

  public String waitForVmIp(final String vmName) throws IOException, InterruptedException {
    log.info("Waiting for IP address of VM {}", vmName);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(infraProperties.getIpWaitTimeoutSeconds()));

    while (Instant.now().isBefore(deadline)) {
      final String output = runAndCapture("get-vm-ip-" + vmName, """
        cd '%s'
        tr -d '\\r' < ./scripts/get-vm-ip.sh | bash -s -- '%s'
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

  public void writeAnsibleInventory(final Map<String, String> vmIps) throws IOException {
    log.info("Writing Ansible inventory for {} VM(s)", vmIps.size());
    final StringBuilder content = new StringBuilder();
    content.append("all:\n");
    content.append("  children:\n");
    content.append("    managed_vms:\n");
    content.append("      hosts:\n");

    for (Map.Entry<String, String> entry : vmIps.entrySet()) {
      content.append("        ").append(entry.getKey()).append(":\n");
      content.append("          ansible_host: ").append(entry.getValue()).append("\n");
      content.append("          ansible_user: ubuntu\n");
      content.append("          ansible_ssh_private_key_file: ")
        .append(infraProperties.getAnsiblePrivateKeyPath()).append("\n");
      content.append("          ansible_ssh_common_args: '")
        .append("-o ProxyJump=root@").append(infraProperties.getVirtualizationHost())
        .append(" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null")
        .append("'\n");
    }

    final Path inventoryPath = repoRoot().resolve(ANSIBLE_INVENTORY_RELATIVE_PATH);
    Files.createDirectories(inventoryPath.getParent());
    Files.writeString(inventoryPath, content.toString(), StandardCharsets.UTF_8);
    log.info("Ansible inventory written to {}", inventoryPath);
  }

  public void runAnsibleForVm(final String vmName) throws IOException, InterruptedException {
    log.info("Running Ansible base playbook for VM {}", vmName);
    final String ansibleDir = repoRootForShell() + "/ansible";
    runInRepo("ansible-" + vmName, """
        export ANSIBLE_CONFIG='%s/ansible.cfg'
        %s \
            -i '%s/inventory/hosts.yml' \
            '%s/playbooks/base.yml' \
            -l '%s'
        """.formatted(
      ansibleDir,
      infraProperties.getAnsiblePlaybookCommand(),
      ansibleDir,
      ansibleDir,
      shellEscape(vmName)
    ));
  }

  public void startVm(final String vmName) throws IOException, InterruptedException {
    log.info("Starting VM {} through libvirt", vmName);
    runInRepo("virsh-start-" + vmName,
      "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh start %s"
        .formatted(infraProperties.getVirtualizationHost(), shellEscape(vmName)));
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
            log.warn("infra[{}][stderr] {}", commandName, sanitizedLine);
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
