package itmo.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "infra")
public class InfraProperties {

    private boolean enabled = true;
    private boolean useWsl = true;
    private String repoRoot = "C:/Projects/Diploma";
    private String wslRepoRoot = "/mnt/c/Projects/Diploma";
    private String virtualizationHost = "89.104.68.81";
    private String virtualizationUser = "root";
    private String virtualizationPrivateKeyPath = "";
    private String vmSshUser = "ubuntu";
    private String ansiblePrivateKeyPath = "/home/kostik/.ssh/id_rsa_vm";
    private String tofuCommand = "tofu";
    private String ansiblePlaybookCommand = "ansible-playbook";
    private int ipWaitTimeoutSeconds = 600;
    private int ipPollIntervalSeconds = 5;
    private int commandTimeoutSeconds = 900;
    private int commandProgressLogIntervalSeconds = 15;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUseWsl() {
        return useWsl;
    }

    public void setUseWsl(final boolean useWsl) {
        this.useWsl = useWsl;
    }

    public String getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(final String repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String getWslRepoRoot() {
        return wslRepoRoot;
    }

    public void setWslRepoRoot(final String wslRepoRoot) {
        this.wslRepoRoot = wslRepoRoot;
    }

    public String getVirtualizationHost() {
        return virtualizationHost;
    }

    public void setVirtualizationHost(final String virtualizationHost) {
        this.virtualizationHost = virtualizationHost;
    }

    public String getVirtualizationUser() {
        return virtualizationUser;
    }

    public void setVirtualizationUser(final String virtualizationUser) {
        this.virtualizationUser = virtualizationUser;
    }

    public String getVirtualizationPrivateKeyPath() {
        return virtualizationPrivateKeyPath;
    }

    public void setVirtualizationPrivateKeyPath(final String virtualizationPrivateKeyPath) {
        this.virtualizationPrivateKeyPath = virtualizationPrivateKeyPath;
    }

    public String getVmSshUser() {
        return vmSshUser;
    }

    public void setVmSshUser(final String vmSshUser) {
        this.vmSshUser = vmSshUser;
    }

    public String getAnsiblePrivateKeyPath() {
        return ansiblePrivateKeyPath;
    }

    public void setAnsiblePrivateKeyPath(final String ansiblePrivateKeyPath) {
        this.ansiblePrivateKeyPath = ansiblePrivateKeyPath;
    }

    public String getTofuCommand() {
        return tofuCommand;
    }

    public void setTofuCommand(final String tofuCommand) {
        this.tofuCommand = tofuCommand;
    }

    public String getAnsiblePlaybookCommand() {
        return ansiblePlaybookCommand;
    }

    public void setAnsiblePlaybookCommand(final String ansiblePlaybookCommand) {
        this.ansiblePlaybookCommand = ansiblePlaybookCommand;
    }

    public int getIpWaitTimeoutSeconds() {
        return ipWaitTimeoutSeconds;
    }

    public void setIpWaitTimeoutSeconds(final int ipWaitTimeoutSeconds) {
        this.ipWaitTimeoutSeconds = ipWaitTimeoutSeconds;
    }

    public int getIpPollIntervalSeconds() {
        return ipPollIntervalSeconds;
    }

    public void setIpPollIntervalSeconds(final int ipPollIntervalSeconds) {
        this.ipPollIntervalSeconds = ipPollIntervalSeconds;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(final int commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public int getCommandProgressLogIntervalSeconds() {
        return commandProgressLogIntervalSeconds;
    }

    public void setCommandProgressLogIntervalSeconds(final int commandProgressLogIntervalSeconds) {
        this.commandProgressLogIntervalSeconds = commandProgressLogIntervalSeconds;
    }
}
