package com.vesoft.nebula.verification;

/** A collector connects only to graphd. Passwords are never written into capture files. */
public final class ConnectionSettings {
    public final String host;
    public final int graphPort;
    public final String user;
    public final String password;
    public int timeoutMs = 60000;

    public ConnectionSettings(String host, int graphPort, String user, String password) {
        if (host == null || host.isEmpty() || graphPort < 1 || graphPort > 65535
                || user == null || password == null) {
            throw new IllegalArgumentException("Invalid graph connection settings");
        }
        this.host = host;
        this.graphPort = graphPort;
        this.user = user;
        this.password = password;
    }
}
