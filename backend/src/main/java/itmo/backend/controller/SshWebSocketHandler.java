package itmo.backend.controller;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import itmo.backend.config.InfraProperties;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.repository.VirtualMachineRepository;
import itmo.backend.services.InfrastructureCommandService;
import itmo.backend.services.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SshWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SshWebSocketHandler.class);

    private final VirtualMachineRepository vmRepository;
    private final JwtService jwtService;
    private final InfraProperties infraProperties;
    private final InfrastructureCommandService infrastructureCommandService;
    private final Map<String, SshConnection> connections = new ConcurrentHashMap<>();

    public SshWebSocketHandler(
            final VirtualMachineRepository vmRepository,
            final JwtService jwtService,
            final InfraProperties infraProperties,
            final InfrastructureCommandService infrastructureCommandService
    ) {
        this.vmRepository = vmRepository;
        this.jwtService = jwtService;
        this.infraProperties = infraProperties;
        this.infrastructureCommandService = infrastructureCommandService;
    }

    @Override
    public void afterConnectionEstablished(final WebSocketSession session) throws Exception {
        final URI uri = session.getUri();
        if (uri == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        final String token = extractQueryParam(uri, "token");
        if (token == null || !jwtService.isTokenValid(token)) {
            session.sendMessage(new TextMessage("Authentication failed\r\n"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        final String vmId = extractVmId(uri.getPath());
        if (vmId == null) {
            session.sendMessage(new TextMessage("Invalid VM ID\r\n"));
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        final VirtualMachine vm = vmRepository.findById(UUID.fromString(vmId)).orElse(null);
        if (vm == null || vm.getIpAddress() == null || vm.getIpAddress().isBlank()) {
            session.sendMessage(new TextMessage("VM not found or has no IP address\r\n"));
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        log.info("Opening SSH terminal to VM {} ({}) for WebSocket session {}",
                vm.getName(), vm.getIpAddress(), session.getId());

        try {
            final SshConnection conn = connectWithFreshIp(vm, session);
            connections.put(session.getId(), conn);
            session.sendMessage(new TextMessage(
                    "Connected to " + vm.getName() + " (" + vm.getIpAddress() + ")\r\n"));
        } catch (final Exception e) {
            log.error("Failed to establish SSH to VM {}: {}", vm.getName(), e.getMessage());
            session.sendMessage(new TextMessage("SSH connection failed: " + e.getMessage() + "\r\n"));
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private SshConnection connectWithFreshIp(final VirtualMachine vm, final WebSocketSession session) throws Exception {
        try {
            return createSshConnection(vm, session);
        } catch (final Exception firstError) {
            final String previousIp = vm.getIpAddress();
            try {
                final String refreshedIp = infrastructureCommandService.waitForVmIp(vm.getName());
                if (refreshedIp != null && !refreshedIp.isBlank() && !refreshedIp.equals(previousIp)) {
                    log.info("Refreshing VM {} IP from {} to {} before retrying SSH", vm.getName(), previousIp, refreshedIp);
                    vm.markIpAddress(refreshedIp, vm.getStatusMessage());
                    vmRepository.save(vm);
                }
            } catch (final Exception refreshError) {
                log.warn("Failed to refresh IP for VM {}: {}", vm.getName(), refreshError.getMessage());
            }

            if (vm.getIpAddress() != null && !vm.getIpAddress().isBlank() && !vm.getIpAddress().equals(previousIp)) {
                return createSshConnection(vm, session);
            }

            throw firstError;
        }
    }

    @Override
    protected void handleTextMessage(final WebSocketSession session, final TextMessage message) throws Exception {
        final SshConnection conn = connections.get(session.getId());
        if (conn != null && conn.outputStream != null) {
            conn.outputStream.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            conn.outputStream.flush();
        }
    }

    @Override
    protected void handleBinaryMessage(final WebSocketSession session, final BinaryMessage message) throws Exception {
        final SshConnection conn = connections.get(session.getId());
        if (conn != null && conn.outputStream != null) {
            conn.outputStream.write(message.getPayload().array());
            conn.outputStream.flush();
        }
    }

    @Override
    public void afterConnectionClosed(final WebSocketSession session, final CloseStatus status) {
        log.info("WebSocket session {} closed: {}", session.getId(), status);
        final SshConnection conn = connections.remove(session.getId());
        if (conn != null) {
            conn.close();
        }
    }

    @Override
    public void handleTransportError(final WebSocketSession session, final Throwable exception) {
        log.warn("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        final SshConnection conn = connections.remove(session.getId());
        if (conn != null) {
            conn.close();
        }
    }

    private SshConnection createSshConnection(
            final VirtualMachine vm,
            final WebSocketSession wsSession
    ) throws Exception {
        final JSch jsch = new JSch();

        final String vmKeyPath = infraProperties.getAnsiblePrivateKeyPath();
        final String jumpKeyPath = infraProperties.getVirtualizationPrivateKeyPath();
        int loadedIdentities = 0;
        if (addIdentityIfExists(jsch, jumpKeyPath, "virtualization host")) {
            loadedIdentities++;
        }
        if (!vmKeyPath.equals(jumpKeyPath)) {
            if (addIdentityIfExists(jsch, vmKeyPath, "vm guest")) {
                loadedIdentities++;
            }
        }

        if (loadedIdentities == 0) {
            throw new IllegalStateException("No valid SSH private keys were loaded. Check infra.virtualization-private-key-path and infra.ansible-private-key-path");
        }

        final String virtHost = infraProperties.getVirtualizationHost();
        final String virtUser = infraProperties.getVirtualizationUser();
        final String vmUser = infraProperties.getVmSshUser();

        final Session jumpSession = jsch.getSession(virtUser, virtHost, 22);
        jumpSession.setConfig("StrictHostKeyChecking", "no");
        jumpSession.setConfig("PreferredAuthentications", "publickey");
        jumpSession.setTimeout(10_000);
        jumpSession.connect();

        final int assignedPort = jumpSession.setPortForwardingL(0, vm.getIpAddress(), 22);

        final Session vmSession = jsch.getSession(vmUser, "127.0.0.1", assignedPort);
        vmSession.setConfig("StrictHostKeyChecking", "no");
        vmSession.setConfig("PreferredAuthentications", "publickey");
        vmSession.setTimeout(10_000);
        vmSession.connect();

        final ChannelShell channel = (ChannelShell) vmSession.openChannel("shell");
        channel.setPtyType("xterm-256color", 120, 30, 0, 0);

        final InputStream inputStream = channel.getInputStream();
        final OutputStream outputStream = channel.getOutputStream();
        channel.connect();

        final Thread readerThread = new Thread(() -> {
            try {
                final byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (wsSession.isOpen()) {
                        wsSession.sendMessage(
                                new TextMessage(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8)));
                    } else {
                        break;
                    }
                }
            } catch (final Exception e) {
                log.debug("SSH reader thread ended: {}", e.getMessage());
            }
        }, "ssh-reader-" + wsSession.getId());
        readerThread.setDaemon(true);
        readerThread.start();

        return new SshConnection(jumpSession, vmSession, channel, outputStream, readerThread);
    }

    private boolean addIdentityIfExists(final JSch jsch, final String keyPath, final String purpose) {
        if (keyPath == null || keyPath.isBlank()) {
            return false;
        }

        if (!Files.exists(Path.of(keyPath))) {
            log.warn("SSH key for {} does not exist: {}", purpose, keyPath);
            return false;
        }

        try {
            jsch.addIdentity(keyPath);
            return true;
        } catch (final Exception exception) {
            log.warn("Failed to load SSH key for {} at {}: {}", purpose, keyPath, exception.getMessage());
            return false;
        }
    }

    private String extractVmId(final String path) {
        final String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("vms".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private String extractQueryParam(final URI uri, final String paramName) {
        final String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (final String pair : query.split("&")) {
            final String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(paramName)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static class SshConnection {
        final Session jumpSession;
        final Session vmSession;
        final ChannelShell channel;
        final OutputStream outputStream;
        final Thread readerThread;

        SshConnection(
                final Session jumpSession,
                final Session vmSession,
                final ChannelShell channel,
                final OutputStream outputStream,
                final Thread readerThread
        ) {
            this.jumpSession = jumpSession;
            this.vmSession = vmSession;
            this.channel = channel;
            this.outputStream = outputStream;
            this.readerThread = readerThread;
        }

        void close() {
            try { channel.disconnect(); } catch (final Exception ignored) { }
            try { vmSession.disconnect(); } catch (final Exception ignored) { }
            try { jumpSession.disconnect(); } catch (final Exception ignored) { }
            readerThread.interrupt();
        }
    }
}
