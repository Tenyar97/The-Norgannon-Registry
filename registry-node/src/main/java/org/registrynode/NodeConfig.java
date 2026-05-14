package org.registrynode;

import java.util.List;


public class NodeConfig {

    private int    port      = 8080;
    private String dataDir   = "./data";
    private String publicUrl = null;
    private List<String> peers;


    public int    getPort()      { return port; }
    public void   setPort(int v) { this.port = v; }

    public String getDataDir()       { return dataDir; }
    public void   setDataDir(String v){ this.dataDir = v; }

    public String getPublicUrl()        { return publicUrl; }
    public void   setPublicUrl(String v){ this.publicUrl = v; }

    public List<String> getPeers()           { return peers; }
    public void         setPeers(List<String> v){ this.peers = v; }


    public void validate() {
        if (port < 1 || port > 65535) {
            throw new IllegalStateException(
                "Config error: node.port must be between 1 and 65535, got " + port);
        }
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalStateException(
                "Config error: node.data_dir must not be blank");
        }
    }
}
