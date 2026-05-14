package org.registryagent.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TrustedServer {

    @JsonProperty("pub_key")
    private String pubKey;

    @JsonProperty("label")
    private String label;

    @JsonProperty("added_at")
    private String addedAt;

    @JsonProperty("default_profile")
    private String defaultProfile;

    public TrustedServer() {}

    public TrustedServer(String pubKey, String label, String addedAt, String defaultProfile) {
        this.pubKey         = pubKey;
        this.label          = label;
        this.addedAt        = addedAt;
        this.defaultProfile = defaultProfile;
    }

    public String getPubKey()           { return pubKey; }
    public void   setPubKey(String v)   { this.pubKey = v; }

    public String getLabel()            { return label; }
    public void   setLabel(String v)    { this.label = v; }

    public String getAddedAt()          { return addedAt; }
    public void   setAddedAt(String v)  { this.addedAt = v; }

    public String getDefaultProfile()          { return defaultProfile; }
    public void   setDefaultProfile(String v)  { this.defaultProfile = v; }

    @Override
    public String toString() {
        return String.format("TrustedServer{label='%s', pubKey='%s...', profile='%s'}",
                label,
                pubKey != null && pubKey.length() > 8 ? pubKey.substring(0, 8) : pubKey,
                defaultProfile);
    }
}
