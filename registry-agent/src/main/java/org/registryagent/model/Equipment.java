package org.registryagent.model;


public class Equipment {

    private String slot;        
    private String namespace;  
    private String refId;       
    private String label;

    public Equipment() {}

    public Equipment(String slot, String namespace, String refId, String label) {
        this.slot = slot;
        this.namespace = namespace;
        this.refId = refId;
        this.label = label;
    }

    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    @Override
    public String toString() {
        return String.format("Equipment{slot='%s', ref='%s:%s', label='%s'}",
                slot, namespace, refId, label);
    }
}
