package com.vesoft.nebula.migration;

/** Connection settings. Passwords are deliberately excluded from exported manifests. */
public final class ConnectionConfig {
    public final String host;
    public final int graphPort;
    public final int metaPort;
    public final String user;
    public final String password;
    public int timeoutMs = 60000;
    /** Time for graph/storage schema caches to observe newly created metadata. */
    public long schemaWaitMillis = 20000;

    public ConnectionConfig(String host, int graphPort, int metaPort,
                            String user, String password) {
        if (host == null || host.isEmpty() || graphPort < 1 || metaPort < 1
                || graphPort > 65535 || metaPort > 65535 || user == null || password == null) {
            throw new IllegalArgumentException("Invalid connection settings");
        }
        this.host = host;
        this.graphPort = graphPort;
        this.metaPort = metaPort;
        this.user = user;
        this.password = password;
    }
}
