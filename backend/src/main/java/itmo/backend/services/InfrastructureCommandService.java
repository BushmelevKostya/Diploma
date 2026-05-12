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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InfrastructureCommandService {

  private static final Logger log = LoggerFactory.getLogger(InfrastructureCommandService.class);
  private static final String GENERATED_TFVARS_RELATIVE_PATH = "tofu/generated/vms.auto.tfvars.json";
  private static final String ANSIBLE_INVENTORY_RELATIVE_PATH = "ansible/inventory/hosts.yml";
  private static final String WSL_ANSIBLE_PRIVATE_KEY_PATH = "/tmp/diploma_ansible_id_rsa";
  private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^([A-Za-z]):[\\\\/](.*)$");

  private final InfraProperties infraProperties;

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
      export TF_CLI_CONFIG_FILE='%s/tofu/tofu.rc'
      cd '%s/tofu'
      %s init -input=false
      %s apply -auto-approve -input=false -var-file='generated/vms.auto.tfvars.json'
      """.formatted(repoRootForShell(), repoRootForShell(), infraProperties.getTofuCommand(), infraProperties.getTofuCommand()));
  }

  public void destroyDesiredState() throws IOException, InterruptedException {
    log.info("Reconciling infrastructure after VM removal");
    runInRepo("opentofu-reconcile", """
      set -euo pipefail
      export TF_CLI_CONFIG_FILE='%s/tofu/tofu.rc'
      cd '%s/tofu'
      %s init -input=false
      %s apply -auto-approve -input=false -var-file='generated/vms.auto.tfvars.json'
      """.formatted(repoRootForShell(), repoRootForShell(), infraProperties.getTofuCommand(), infraProperties.getTofuCommand()));
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
    String command = String.format(
      "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 root@%s -- virsh snapshot-revert %s %s --force",
      infraProperties.getVirtualizationHost(),
      shellEscape(vmName),
      shellEscape(snapshotName)
    );

    runInRepo("virsh-snapshot-restore-" + vmName + "-" + snapshotName, command);
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
