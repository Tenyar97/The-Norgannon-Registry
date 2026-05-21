package org.registrynode.model;

import com.fasterxml.jackson.annotation.JsonInclude;

public class Equipment {

	private String slot;
	private String namespace;
	private String refId;
	private String label;
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private int durability;

	public Equipment() {
	}

	public Equipment(String slot, String namespace, String refId, String label, int durability) {
		this.slot = slot;
		this.namespace = namespace;
		this.refId = refId;
		this.label = label;
		this.durability = durability;
	}

	public String getSlot() {
		return slot;
	}

	public void setSlot(String slot) {
		this.slot = slot;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getRefId() {
		return refId;
	}

	public void setRefId(String refId) {
		this.refId = refId;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public int getDurability() {
		return durability;
	}

	public void setDurability(int durability) {
		this.durability = durability;
	}

	@Override
	public String toString() {
		return String.format("Equipment{slot='%s', ref='%s:%s', label='%s', durability=%d}", slot, namespace, refId, label, durability);
	}
}
