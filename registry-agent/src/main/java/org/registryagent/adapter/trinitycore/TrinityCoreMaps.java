package org.registryagent.adapter.trinitycore;

import java.util.Map;

public class TrinityCoreMaps {

	public static final Map<Integer, String> CLASS_ID_TO_REF = Map.ofEntries(Map.entry(1, "warrior"),
			Map.entry(2, "paladin"), Map.entry(3, "hunter"), Map.entry(4, "rogue"), Map.entry(5, "priest"),
			Map.entry(6, "death_knight"), Map.entry(7, "shaman"), Map.entry(8, "mage"), Map.entry(9, "warlock"),
			Map.entry(11, "druid"));

	public static final Map<String, Integer> CLASS_REF_TO_ID = CLASS_ID_TO_REF.entrySet().stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

	public static final Map<Integer, String> RACE_ID_TO_REF = Map.ofEntries(Map.entry(1, "human"), Map.entry(2, "orc"),
			Map.entry(3, "dwarf"), Map.entry(4, "night_elf"), Map.entry(5, "undead"), Map.entry(6, "tauren"),
			Map.entry(7, "gnome"), Map.entry(8, "troll"), Map.entry(10, "blood_elf"), Map.entry(11, "draenei"));

	public static final Map<String, Integer> RACE_REF_TO_ID = RACE_ID_TO_REF.entrySet().stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

	public static final Map<Integer, String> GENDER_ID_TO_REF = Map.of(0, "male", 1, "female", 2, "unknown");

	public static final Map<String, Integer> GENDER_REF_TO_ID = Map.of("male", 0, "female", 1, "unknown", 2);

	public static final String[] SLOT_NAMES = { "head", // 0
			"neck", // 1
			"shoulders", // 2
			"body", // 3 (shirt)
			"chest", // 4
			"waist", // 5
			"legs", // 6
			"feet", // 7
			"wrists", // 8
			"hands", // 9
			"finger1", // 10
			"finger2", // 11
			"trinket1", // 12
			"trinket2", // 13
			"back", // 14
			"mainhand", // 15
			"offhand", // 16
			"ranged", // 17
			"tabard" // 18
	};

	public static final Map<Integer, String> POWER_ID_TO_NAME = Map.of(1, "mana", 2, "rage", 3, "focus", 4, "energy", 5,
			"happiness", 6, "rune", 7, "runic_power");

	public static final Map<Integer, String> PET_TYPE_TO_REF = Map.of(
			0, "warlock",
			1, "hunter"
	);

	public static String petSlotLabel(int slot) {
		return slot == 0 ? "active" : "stable_" + slot;
	}

	public static final Map<Integer, String> MAP_ID_TO_NAME = Map.of(
			0,   "Eastern Kingdoms",
			1,   "Kalimdor",
			530, "Outland",
			571, "Northrend"
	);

	public static final Map<Integer, String> ZONE_ID_TO_NAME = Map.ofEntries(
			
			Map.entry(1519, "Stormwind City"),
			Map.entry(1537, "Ironforge"),
			Map.entry(1657, "Darnassus"),
			Map.entry(3557, "The Exodar"),
			
			Map.entry(1637, "Orgrimmar"),
			Map.entry(1638, "Thunder Bluff"),
			Map.entry(1439, "Undercity"),
			Map.entry(3487, "Silvermoon City"),
			Map.entry(3703, "Shattrath City"),
			Map.entry(4395, "Dalaran"),
			Map.entry(12,   "Elwynn Forest"),
			Map.entry(1,    "Dun Morogh"),
			Map.entry(141,  "Teldrassil"),
			Map.entry(3524, "Azuremyst Isle"),
			Map.entry(14,   "Durotar"),
			Map.entry(85,   "Tirisfal Glades"),
			Map.entry(215,  "Mulgore"),
			Map.entry(3430, "Eversong Woods"),
			Map.entry(148,  "Darkshore"),
			Map.entry(17,   "Barrens"),
			Map.entry(44,   "Redridge Mountains"),
			Map.entry(3483, "Hellfire Peninsula"),
			Map.entry(3518, "Nagrand"),
			Map.entry(3519, "Terokkar Forest"),
			Map.entry(3522, "Blade's Edge Mountains"),
			Map.entry(3523, "Netherstorm"),
			Map.entry(3525, "Shadowmoon Valley"),
			Map.entry(4197, "Borean Tundra"),
			Map.entry(495,  "Howling Fjord"),
			Map.entry(4298, "Icecrown"),
			Map.entry(4812, "The Storm Peaks"),
			Map.entry(4394, "Grizzly Hills"),
			Map.entry(4710, "Crystalsong Forest"),
			Map.entry(65,   "Dragonblight"),
			Map.entry(4742, "Hrothgar's Landing"),
			Map.entry(4281, "Zul'Drak"),
			Map.entry(4333, "Sholazar Basin")
	);

	public static final Map<Integer, Integer> CLASS_PRIMARY_POWER = Map.ofEntries(
			Map.entry(1,  2),
			Map.entry(2,  1), 
			Map.entry(3,  1), 
			Map.entry(4,  4), 
			Map.entry(5,  1),
			Map.entry(6,  7), 
			Map.entry(7,  1),
			Map.entry(8,  1),
			Map.entry(9,  1),
			Map.entry(11, 1)
	);

	private TrinityCoreMaps() {
	}
}
