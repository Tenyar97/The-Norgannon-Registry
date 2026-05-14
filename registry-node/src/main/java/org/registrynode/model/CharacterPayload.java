package org.registrynode.model;

import java.util.List;
import java.util.Map;

public class CharacterPayload {

	private CharacterIdentity identity;
	private CharacterStats stats;
	private List<Equipment> equipment;
	private List<InventoryItem> inventory;
	private List<InventoryItem> bank;
	private Currency currency;
	private Progression progression;
	private List<Pet> pets;
	private HearthstoneLocation hearthstone;

	private Map<String, Object> extensions;

	public CharacterPayload() {
	}

	public CharacterIdentity getIdentity() {
		return identity;
	}

	public void setIdentity(CharacterIdentity identity) {
		this.identity = identity;
	}

	public CharacterStats getStats() {
		return stats;
	}

	public void setStats(CharacterStats stats) {
		this.stats = stats;
	}

	public List<Equipment> getEquipment() {
		return equipment;
	}

	public void setEquipment(List<Equipment> equipment) {
		this.equipment = equipment;
	}

	public List<InventoryItem> getInventory() {
		return inventory;
	}

	public void setInventory(List<InventoryItem> inventory) {
		this.inventory = inventory;
	}

	public List<InventoryItem> getBank() {
		return bank;
	}

	public void setBank(List<InventoryItem> bank) {
		this.bank = bank;
	}

	public Currency getCurrency() {
		return currency;
	}

	public void setCurrency(Currency currency) {
		this.currency = currency;
	}

	public Progression getProgression() {
		return progression;
	}

	public void setProgression(Progression progression) {
		this.progression = progression;
	}

	public List<Pet> getPets() {
		return pets;
	}

	public void setPets(List<Pet> pets) {
		this.pets = pets;
	}

	public HearthstoneLocation getHearthstone() {
		return hearthstone;
	}

	public void setHearthstone(HearthstoneLocation hearthstone) {
		this.hearthstone = hearthstone;
	}

	public Map<String, Object> getExtensions() {
		return extensions;
	}

	public void setExtensions(Map<String, Object> extensions) {
		this.extensions = extensions;
	}
}
