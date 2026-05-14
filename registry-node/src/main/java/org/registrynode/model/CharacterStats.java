package org.registrynode.model;

import java.util.Map;

public class CharacterStats {

	private int strength;
	private int agility;
	private int stamina;
	private int intellect;
	private int spirit;

	private int health;
	private int mana;
	private int attackPower;
	private int spellPower;
	private int defense;

	private Map<String, Map<String, Integer>> statExtensions;

	public CharacterStats() {
	}

	public int getStrength() {
		return strength;
	}

	public void setStrength(int strength) {
		this.strength = strength;
	}

	public int getAgility() {
		return agility;
	}

	public void setAgility(int agility) {
		this.agility = agility;
	}

	public int getStamina() {
		return stamina;
	}

	public void setStamina(int stamina) {
		this.stamina = stamina;
	}

	public int getIntellect() {
		return intellect;
	}

	public void setIntellect(int intellect) {
		this.intellect = intellect;
	}

	public int getSpirit() {
		return spirit;
	}

	public void setSpirit(int spirit) {
		this.spirit = spirit;
	}

	public int getHealth() {
		return health;
	}

	public void setHealth(int health) {
		this.health = health;
	}

	public int getMana() {
		return mana;
	}

	public void setMana(int mana) {
		this.mana = mana;
	}

	public int getAttackPower() {
		return attackPower;
	}

	public void setAttackPower(int attackPower) {
		this.attackPower = attackPower;
	}

	public int getSpellPower() {
		return spellPower;
	}

	public void setSpellPower(int spellPower) {
		this.spellPower = spellPower;
	}

	public int getDefense() {
		return defense;
	}

	public void setDefense(int defense) {
		this.defense = defense;
	}

	public Map<String, Map<String, Integer>> getStatExtensions() {
		return statExtensions;
	}

	public void setStatExtensions(Map<String, Map<String, Integer>> statExtensions) {
		this.statExtensions = statExtensions;
	}

	public Map<String, Integer> getExtensionsFor(String namespace) {
		if (statExtensions == null)
			return null;
		return statExtensions.get(namespace);
	}
}
