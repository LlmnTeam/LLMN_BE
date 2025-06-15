package com.example.llmn.integration.minasshd;

public record SshConnectionConfig(String host, String username, String privateKeyPath) {

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
}
