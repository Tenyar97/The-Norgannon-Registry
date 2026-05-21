package org.registryagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;


@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventoryItem {

	private String location;
	private long bag;
	private int slot;
	private long itemGuid;
	private String namespace;
	private String refId;
	private int count;
	private int durability;

	private String templateBlob;
	private String itemText;

	public InventoryItem() {
	}

	public InventoryItem(String location, long bag, int slot, long itemGuid, String namespace, String refId, int count,
			int durability, String templateBlob) {
		this.location = location;
		this.bag = bag;
		this.slot = slot;
		this.itemGuid = itemGuid;
		this.namespace = namespace;
		this.refId = refId;
		this.count = count;
		this.durability = durability;
		this.templateBlob = templateBlob;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public long getBag() {
		return bag;
	}

	public void setBag(long bag) {
		this.bag = bag;
	}

	public int getSlot() {
		return slot;
	}

	public void setSlot(int slot) {
		this.slot = slot;
	}

	public long getItemGuid() {
		return itemGuid;
	}

	public void setItemGuid(long itemGuid) {
		this.itemGuid = itemGuid;
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

	public int getCount() {
		return count;
	}

	public void setCount(int count) {
		this.count = count;
	}

	public int getDurability() {
		return durability;
	}

	public void setDurability(int durability) {
		this.durability = durability;
	}

	public String getTemplateBlob() {
		return templateBlob;
	}

	public void setTemplateBlob(String templateBlob) {
		this.templateBlob = templateBlob;
	}

	public String getItemText() {
		return itemText;
	}

	public void setItemText(String itemText) {
		this.itemText = itemText;
	}
}
