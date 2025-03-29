package com.example.llmn.integration.minasshd;

public class SshConnectionConfig {

    private final String host;
    private final String username;
    private final String privateKeyPath;

    public SshConnectionConfig(String host, String username, String privateKeyPath) {
        this.host = host;
        this.username = username;
        this.privateKeyPath = privateKeyPath;
    }

    public String getHost() {
        return host;
    }

    public String getUsername() {
        return username;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    @Override
    public String toString() {
        return username + "@" + host;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SshConnectionConfig that = (SshConnectionConfig) o;
        if (!host.equals(that.host)) return false;
        if (!username.equals(that.username)) return false;
        return privateKeyPath.equals(that.privateKeyPath);
    }

    @Override
    public int hashCode() {
        int result = host.hashCode();
        result = 31 * result + username.hashCode();
        result = 31 * result + privateKeyPath.hashCode();
        return result;
    }
}
