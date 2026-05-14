package org.registryagent.model;


public class Pet {

	private String name; 
	private String namespace; 
	private String creatureRef; 
	private int level;
	private String petType;
	private String slot; 
	private int currentHealth;
	private int currentMana;
	private int happiness; // 0–1000000, hunter pets only; 0 for warlock demons

	public Pet() {
	}

	public Pet(String name, String namespace, String creatureRef, int level, String petType, String slot,
			int currentHealth, int currentMana, int happiness) {
		this.name = name;
		this.namespace = namespace;
		this.creatureRef = creatureRef;
		this.level = level;
		this.petType = petType;
		this.slot = slot;
		this.currentHealth = currentHealth;
		this.currentMana = currentMana;
		this.happiness = happiness;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getCreatureRef() {
		return creatureRef;
	}

	public void setCreatureRef(String creatureRef) {
		this.creatureRef = creatureRef;
	}

	public int getLevel() {
		return level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public String getPetType() {
		return petType;
	}

	public void setPetType(String petType) {
		this.petType = petType;
	}

	public String getSlot() {
		return slot;
	}

	public void setSlot(String slot) {
		this.slot = slot;
	}

	public int getCurrentHealth() {
		return currentHealth;
	}

	public void setCurrentHealth(int currentHealth) {
		this.currentHealth = currentHealth;
	}

	public int getCurrentMana() {
		return currentMana;
	}

	public void setCurrentMana(int currentMana) {
		this.currentMana = currentMana;
	}

	public int getHappiness() {
		return happiness;
	}

	public void setHappiness(int happiness) {
		this.happiness = happiness;
	}

	@Override
	public String toString() {
		return String.format("Pet{name='%s', type='%s', slot='%s', level=%d, ref='%s:%s'}", name, petType, slot, level,
				namespace, creatureRef);
	}
}
