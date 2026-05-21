package org.registryagent.snapshot;

import org.registryagent.model.*;
import org.registryagent.model.Currency;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ImportProfileFilter {

	private static final Logger log = Logger.getLogger(ImportProfileFilter.class.getName());

	private ImportProfileFilter() {
	}

	public static CharacterPayload apply(CharacterPayload source, ImportProfile profile) {
		if (profile == null)
			return source;

		CharacterPayload out = new CharacterPayload();

		out.setIdentity(applyIdentityCap(source.getIdentity(), profile));

		out.setStats(source.getStats());

		out.setEquipment(profile.isImportEquipment() ? source.getEquipment() : null);

		if (profile.isImportInventory()) {
			out.setInventory(profile.isImportCustomItemTemplates() ? source.getInventory()
					: stripTemplateBlobs(source.getInventory()));
		}

		if (profile.isImportBank()) {
			out.setBank(
					profile.isImportCustomItemTemplates() ? source.getBank() : stripTemplateBlobs(source.getBank()));
		}

		out.setCurrency(applyCurrencyCap(source.getCurrency(), profile));

		out.setHearthstone(profile.isImportHearthstone() ? source.getHearthstone() : null);

		out.setPets(profile.isImportPets() ? source.getPets() : null);

		out.setProgression(applyProgressionFilter(source.getProgression(), profile));

		return out;
	}

	private static CharacterIdentity applyIdentityCap(CharacterIdentity src, ImportProfile profile) {
		if (src == null)
			return null;

		int cap = profile.getMaxLevel();
		if (cap <= 0 || src.getLevel() <= cap) {
			return src;
		}

		log.info("ImportProfileFilter: capping level " + src.getLevel() + " → " + cap + " (profile=" + profile.getName()
				+ ")");

		CharacterIdentity capped = new CharacterIdentity();
		capped.setName(src.getName());
		capped.setGender(src.getGender());
		capped.setLevel(cap);
		capped.setClassRef(src.getClassRef());
		capped.setClassNamespace(src.getClassNamespace());
		capped.setClassLabel(src.getClassLabel());
		capped.setRaceRef(src.getRaceRef());
		capped.setRaceNamespace(src.getRaceNamespace());
		capped.setRaceLabel(src.getRaceLabel());
		return capped;
	}

	private static Currency applyCurrencyCap(Currency src, ImportProfile profile) {
		if (src == null)
			return null;

		long cap = profile.getMaxGoldCopper();
		if (cap <= 0) {
			return src;
		}

		Map<String, Long> values = src.getValues();
		if (values == null || !values.containsKey("copper"))
			return src;

		long copper = values.get("copper");
		if (copper <= cap) {
			return src;
		}

		log.info("ImportProfileFilter: capping gold " + (copper / 10_000) + "g → " + (cap / 10_000) + "g (profile="
				+ profile.getName() + ")");

		long gold = cap / 10_000;
		long silver = (cap % 10_000) / 100;
		long rem = cap % 100;

		Map<String, Long> cappedValues = new LinkedHashMap<>(values);
		cappedValues.put("copper", cap);
		cappedValues.put("gold", gold);
		cappedValues.put("silver", silver);
		cappedValues.put("copper_remainder", rem);

		return new Currency(src.getNamespace(), cappedValues);
	}

	private static Progression applyProgressionFilter(Progression src, ImportProfile profile) {
		if (src == null)
			return null;

		Progression out = new Progression();

		if (profile.isImportSkills()) {
			out.setSkills(filterSkills(src.getSkills(), profile));
		}

		if (profile.isImportReputation()) {
			out.setReputation(filterReputation(src.getReputation(), profile));
		}

		out.setQuestsCompleted(profile.isImportQuestsCompleted() ? src.getQuestsCompleted() : null);

		out.setTalents(profile.isImportTalents() ? src.getTalents() : null);

		out.setSpells(profile.isImportSpells() ? src.getSpells() : null);

		out.setFlags(src.getFlags());

		return out;
	}

	private static List<InventoryItem> stripTemplateBlobs(List<InventoryItem> items) {
		if (items == null)
			return null;
		return items.stream().map(item -> {
			if (item.getTemplateBlob() == null)
				return item; // nothing to strip
			InventoryItem stripped = new InventoryItem(item.getLocation(), item.getBag(), item.getSlot(),
					item.getItemGuid(), item.getNamespace(), item.getRefId(), item.getCount(), item.getDurability(),
					null);
			return stripped;
		}).collect(Collectors.toList());
	}

	private static Map<String, Map<String, Integer>> filterSkills(Map<String, Map<String, Integer>> src,
			ImportProfile profile) {

		if (src == null)
			return null;

		Set<Integer> blocked = profile.getBlockedSkillIds();
		if (blocked == null || blocked.isEmpty())
			return src;

		Map<String, Map<String, Integer>> filteredNs = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Integer>> nsEntry : src.entrySet()) {
			Map<String, Integer> filteredSkills = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> skill : nsEntry.getValue().entrySet()) {
				try {
					int id = Integer.parseInt(skill.getKey());
					if (!blocked.contains(id)) {
						filteredSkills.put(skill.getKey(), skill.getValue());
					}
				} catch (NumberFormatException ignored) {
					filteredSkills.put(skill.getKey(), skill.getValue());
				}
			}
			if (!filteredSkills.isEmpty()) {
				filteredNs.put(nsEntry.getKey(), filteredSkills);
			}
		}
		return filteredNs.isEmpty() ? null : filteredNs;
	}

	private static Map<String, Map<String, Integer>> filterReputation(Map<String, Map<String, Integer>> src,
			ImportProfile profile) {

		if (src == null)
			return null;

		Set<Integer> blocked = profile.getBlockedFactionIds();
		if (blocked == null || blocked.isEmpty())
			return src;

		Map<String, Map<String, Integer>> filteredNs = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Integer>> nsEntry : src.entrySet()) {
			Map<String, Integer> filteredFactions = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> faction : nsEntry.getValue().entrySet()) {
				try {
					int id = Integer.parseInt(faction.getKey());
					if (!blocked.contains(id)) {
						filteredFactions.put(faction.getKey(), faction.getValue());
					}
				} catch (NumberFormatException ignored) {
					filteredFactions.put(faction.getKey(), faction.getValue());
				}
			}
			if (!filteredFactions.isEmpty()) {
				filteredNs.put(nsEntry.getKey(), filteredFactions);
			}
		}
		return filteredNs.isEmpty() ? null : filteredNs;
	}
}
