package org.registryagent.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportProfile {

	private String name = "default";
	private String description = "";

	@JsonProperty("max_level")
	private int maxLevel = 0;

	@JsonProperty("max_gold_copper")
	private long maxGoldCopper = 0;

	@JsonProperty("import_equipment")
	private boolean importEquipment = true;

	@JsonProperty("import_inventory")
	private boolean importInventory = true;

	@JsonProperty("import_bank")
	private boolean importBank = true;

	@JsonProperty("import_pets")
	private boolean importPets = true;

	@JsonProperty("import_hearthstone")
	private boolean importHearthstone = true;

	@JsonProperty("import_skills")
	private boolean importSkills = true;

	@JsonProperty("import_reputation")
	private boolean importReputation = true;

	@JsonProperty("import_quests_completed")
	private boolean importQuestsCompleted = true;

	@JsonProperty("import_talents")
	private boolean importTalents = true;

	@JsonProperty("import_spells")
	private boolean importSpells = false;

	@JsonProperty("import_custom_item_templates")
	private boolean importCustomItemTemplates = true;

	@JsonProperty("blocked_skill_ids")
	private Set<Integer> blockedSkillIds = Collections.emptySet();

	@JsonProperty("blocked_faction_ids")
	private Set<Integer> blockedFactionIds = Collections.emptySet();

	public static ImportProfile permissive() {
		return new ImportProfile();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String d) {
		this.description = d;
	}

	public int getMaxLevel() {
		return maxLevel;
	}

	public void setMaxLevel(int maxLevel) {
		this.maxLevel = maxLevel;
	}

	public long getMaxGoldCopper() {
		return maxGoldCopper;
	}

	public void setMaxGoldCopper(long v) {
		this.maxGoldCopper = v;
	}

	public boolean isImportEquipment() {
		return importEquipment;
	}

	public void setImportEquipment(boolean v) {
		this.importEquipment = v;
	}

	public boolean isImportInventory() {
		return importInventory;
	}

	public void setImportInventory(boolean v) {
		this.importInventory = v;
	}

	public boolean isImportBank() {
		return importBank;
	}

	public void setImportBank(boolean v) {
		this.importBank = v;
	}

	public boolean isImportPets() {
		return importPets;
	}

	public void setImportPets(boolean v) {
		this.importPets = v;
	}

	public boolean isImportHearthstone() {
		return importHearthstone;
	}

	public void setImportHearthstone(boolean v) {
		this.importHearthstone = v;
	}

	public boolean isImportSkills() {
		return importSkills;
	}

	public void setImportSkills(boolean v) {
		this.importSkills = v;
	}

	public boolean isImportReputation() {
		return importReputation;
	}

	public void setImportReputation(boolean v) {
		this.importReputation = v;
	}

	public boolean isImportQuestsCompleted() {
		return importQuestsCompleted;
	}

	public void setImportQuestsCompleted(boolean v) {
		this.importQuestsCompleted = v;
	}

	public boolean isImportTalents() {
		return importTalents;
	}

	public void setImportTalents(boolean v) {
		this.importTalents = v;
	}

	public boolean isImportSpells() {
		return importSpells;
	}

	public void setImportSpells(boolean v) {
		this.importSpells = v;
	}

	public Set<Integer> getBlockedSkillIds() {
		return blockedSkillIds;
	}

	public void setBlockedSkillIds(Set<Integer> s) {
		this.blockedSkillIds = s;
	}

	public Set<Integer> getBlockedFactionIds() {
		return blockedFactionIds;
	}

	public void setBlockedFactionIds(Set<Integer> s) {
		this.blockedFactionIds = s;
	}

	public boolean isImportCustomItemTemplates() {
		return importCustomItemTemplates;
	}

	public void setImportCustomItemTemplates(boolean v) {
		this.importCustomItemTemplates = v;
	}

	@Override
	public String toString() {
		return String.format(
				"ImportProfile{name='%s', maxLevel=%d, maxGold=%dg, equip=%b, inv=%b, bank=%b, "
						+ "pets=%b, hs=%b, skills=%b, rep=%b, quests=%b, talents=%b, spells=%b, customTemplates=%b}",
				name, maxLevel, maxGoldCopper / 10_000, importEquipment, importInventory, importBank, importPets,
				importHearthstone, importSkills, importReputation, importQuestsCompleted, importTalents, importSpells,
				importCustomItemTemplates);
	}
}
