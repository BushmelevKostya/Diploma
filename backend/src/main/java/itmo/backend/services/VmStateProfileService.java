package itmo.backend.services;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import itmo.backend.config.InfraProperties;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.exceptions.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class VmStateProfileService {

    private static final List<String> PROFILE_COMMANDS = List.of(
        "HOSTNAME=$(hostnamectl --static 2>/dev/null || hostname)",
        "SSH_PACKAGE=$(dpkg-query -W -f='${Status}' openssh-server 2>/dev/null | grep -q 'install ok installed' && echo true || echo false)",
        "DOCKER_PACKAGE=$(dpkg-query -W -f='${Status}' docker.io 2>/dev/null | grep -q 'install ok installed' && echo true || echo false)",
        "PYTHON3_PACKAGE=$(dpkg-query -W -f='${Status}' python3 2>/dev/null | grep -q 'install ok installed' && echo true || echo false)",
        "SSH_ENABLED=$(systemctl is-enabled ssh >/dev/null 2>&1 && echo true || echo false)",
        "SSH_ACTIVE=$(systemctl is-active ssh >/dev/null 2>&1 && echo true || echo false)",
        "DOCKER_ENABLED=$(systemctl is-enabled docker >/dev/null 2>&1 && echo true || echo false)",
        "DOCKER_ACTIVE=$(systemctl is-active docker >/dev/null 2>&1 && echo true || echo false)",
        "DOCKER_GROUP_UBUNTU=$(id -nG ubuntu 2>/dev/null | tr ' ' '\\n' | grep -qx docker && echo true || echo false)",
        "HTTP_DEMO_COMMAND=$(test -x /usr/local/bin/diploma-http-demo && echo true || echo false)",
        "HTTP_DEMO_INDEX=$(test -f /var/www/diploma-empty/index.html && echo true || echo false)",
        "printf 'hostname=%s\\n' \"$HOSTNAME\"",
        "printf 'sshPackageInstalled=%s\\n' \"$SSH_PACKAGE\"",
        "printf 'dockerPackageInstalled=%s\\n' \"$DOCKER_PACKAGE\"",
        "printf 'python3Installed=%s\\n' \"$PYTHON3_PACKAGE\"",
        "printf 'sshEnabled=%s\\n' \"$SSH_ENABLED\"",
        "printf 'sshActive=%s\\n' \"$SSH_ACTIVE\"",
        "printf 'dockerEnabled=%s\\n' \"$DOCKER_ENABLED\"",
        "printf 'dockerActive=%s\\n' \"$DOCKER_ACTIVE\"",
        "printf 'dockerGroupUbuntu=%s\\n' \"$DOCKER_GROUP_UBUNTU\"",
        "printf 'httpDemoCommand=%s\\n' \"$HTTP_DEMO_COMMAND\"",
        "printf 'httpDemoIndex=%s\\n' \"$HTTP_DEMO_INDEX\""
    );

    private final InfraProperties infraProperties;

    public VmStateProfileService(final InfraProperties infraProperties) {
        this.infraProperties = infraProperties;
    }

    public Map<String, Object> captureProfile(final VirtualMachine vm) {
        if (vm.getIpAddress() == null || vm.getIpAddress().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VM has no IP address for state capture");
        }

        try {
            return doCaptureProfile(vm);
        } catch (final Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VM state capture failed: " + rootMessage(exception));
        }
    }

    private Map<String, Object> doCaptureProfile(final VirtualMachine vm) throws Exception {
        final JSch jsch = new JSch();
        loadIdentityIfExists(jsch, infraProperties.getVirtualizationPrivateKeyPath(), "virtualization host");
        if (!infraProperties.getAnsiblePrivateKeyPath().equals(infraProperties.getVirtualizationPrivateKeyPath())) {
            loadIdentityIfExists(jsch, infraProperties.getAnsiblePrivateKeyPath(), "vm guest");
        }

        final Session jumpSession = jsch.getSession(
            infraProperties.getVirtualizationUser(),
            infraProperties.getVirtualizationHost(),
            22
        );
        jumpSession.setConfig("StrictHostKeyChecking", "no");
        jumpSession.setConfig("PreferredAuthentications", "publickey");
        jumpSession.setTimeout(10_000);
        jumpSession.connect();

        try {
            final int assignedPort = jumpSession.setPortForwardingL(0, vm.getIpAddress(), 22);
            final Session vmSession = jsch.getSession(infraProperties.getVmSshUser(), "127.0.0.1", assignedPort);
            vmSession.setConfig("StrictHostKeyChecking", "no");
            vmSession.setConfig("PreferredAuthentications", "publickey");
            vmSession.setTimeout(10_000);
            vmSession.connect();

            try {
                final String output = executeProfileCommand(vmSession);
                return parseProfileOutput(output);
            } finally {
                vmSession.disconnect();
            }
        } finally {
            jumpSession.disconnect();
        }
    }

    private String executeProfileCommand(final Session vmSession) throws Exception {
        final ChannelExec channel = (ChannelExec) vmSession.openChannel("exec");
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        channel.setOutputStream(stdout);
        channel.setErrStream(stderr);
        channel.setCommand("bash -lc " + quoteForShell(String.join("; ", PROFILE_COMMANDS)));
        channel.connect(10_000);

        while (!channel.isClosed()) {
            Thread.sleep(100);
        }

        final int exitStatus = channel.getExitStatus();
        channel.disconnect();

        if (exitStatus != 0) {
            final String errorOutput = stderr.toString(StandardCharsets.UTF_8).trim();
            throw new IllegalStateException(errorOutput.isBlank() ? "Profile command failed" : errorOutput);
        }

        return stdout.toString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> parseProfileOutput(final String output) {
        final Map<String, Object> profile = new LinkedHashMap<>();
        for (final String rawLine : output.split("\\R")) {
            final String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || !line.contains("=")) {
                continue;
            }

            final int separator = line.indexOf('=');
            final String key = line.substring(0, separator);
            final String value = line.substring(separator + 1);
            profile.put(key, parseValue(value));
        }

        return profile;
    }

    private Object parseValue(final String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }

        return value;
    }

    private void loadIdentityIfExists(final JSch jsch, final String keyPath, final String purpose) throws Exception {
        if (keyPath == null || keyPath.isBlank()) {
            return;
        }

        if (!Files.exists(Path.of(keyPath))) {
            throw new IllegalStateException("SSH key for " + purpose + " does not exist: " + keyPath);
        }

        jsch.addIdentity(keyPath);
    }

    private String quoteForShell(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String rootMessage(final Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank() ? "unknown error" : message.trim();
    }
}
