package org.registryagent.adapter.trinitycore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.registryagent.adapter.ServerAdapter;
import org.registryagent.model.*;
import org.registryagent.model.Currency;
import org.registryagent.model.HearthstoneLocation;
import org.registryagent.model.Pet;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class TrinityCoreAdapter implements ServerAdapter {

	private static final Logger log = Logger.getLogger(TrinityCoreAdapter.class.getName());

	public static final String NAMESPACE = "wow_wotlk_3.3.5a";

	private final HikariDataSource dataSource;

	public TrinityCoreAdapter(String jdbcUrl, String username, String password) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(jdbcUrl);
		config.setUsername(username);
		config.setPassword(password);
		config.setMaximumPoolSize(10);
		config.setConnectionTimeout(5000);
		config.setPoolName("trinitycore-adapter");
		this.dataSource = new HikariDataSource(config);
		ensureMappingTable();
	}

	@Override
	public String getNamespace() {
		return NAMESPACE;
	}

	@Override
	public boolean characterExistsLocally(String characterId) {
		Integer guid = resolveGuid(characterId);
		return guid != null;
	}

	@Override
	public CharacterPayload readCharacter(String characterId) {
		Integer guid = resolveGuid(characterId);
		if (guid == null) {
			log.warning("No local character found for registry UUID: " + characterId);
			return null;
		}
		try {
			return buildPayload(guid);
		} catch (SQLException e) {
			log.severe("DB error reading character guid=" + guid + ": " + e.getMessage());
			return null;
		}
	}

	@Override
	public boolean writeCharacter(String characterId, CharacterPayload payload) {
		Integer guid = resolveGuid(characterId);
		if (guid == null) {
			log.warning("writeCharacter called but no local character mapped for: " + characterId);
			return false;
		}
		try {
			updateFromPayload(guid, payload);
			return true;
		} catch (SQLException e) {
			log.severe("DB error writing character guid=" + guid + ": " + e.getMessage());
			return false;
		}
	}

	private CharacterPayload buildPayload(int guid) throws SQLException {
		CharacterPayload payload = new CharacterPayload();

		try (Connection conn = dataSource.getConnection()) {
			payload.setIdentity(readIdentity(conn, guid));
			payload.setStats(readStats(conn, guid));
			payload.setEquipment(readEquipment(conn, guid));
			InventorySplit inventory = readInventorySplit(conn, guid);
			payload.setInventory(inventory.inventory());
			payload.setBank(inventory.bank());
			payload.setCurrency(readCurrency(conn, guid));
			payload.setProgression(readProgression(conn, guid));
			payload.setPets(readPets(conn, guid));
			payload.setHearthstone(readHearthstone(conn, guid));
		}

		return payload;
	}


	private CharacterIdentity readIdentity(Connection conn, int guid) throws SQLException {
		String sql = "SELECT name, race, class, gender, level FROM characters WHERE guid = ?";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next())
					throw new SQLException("Character not found: guid=" + guid);

				int raceId = rs.getInt("race");
				int classId = rs.getInt("class");
				int genderId = rs.getInt("gender");

				CharacterIdentity identity = new CharacterIdentity();
				identity.setName(rs.getString("name"));
				identity.setLevel(rs.getInt("level"));
				identity.setGender(TrinityCoreMaps.GENDER_ID_TO_REF.getOrDefault(genderId, "unknown"));

				identity.setClassRef(TrinityCoreMaps.CLASS_ID_TO_REF.getOrDefault(classId, "unknown_" + classId));
				identity.setClassNamespace(NAMESPACE);
				identity.setClassLabel(capitalize(TrinityCoreMaps.CLASS_ID_TO_REF.getOrDefault(classId, "Unknown")));

				identity.setRaceRef(TrinityCoreMaps.RACE_ID_TO_REF.getOrDefault(raceId, "unknown_" + raceId));
				identity.setRaceNamespace(NAMESPACE);
				identity.setRaceLabel(capitalize(TrinityCoreMaps.RACE_ID_TO_REF.getOrDefault(raceId, "Unknown")));

				return identity;
			}
		}
	}

	protected CharacterStats readStats(Connection conn, int guid) throws SQLException {
		String sql = "SELECT class, health, power1, power2, power3, power4, power5, power6, power7 "
				+ "FROM characters WHERE guid = ?";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next())
					throw new SQLException("Character not found: guid=" + guid);

				int classId = rs.getInt("class");
				int primaryPowerIdx = TrinityCoreMaps.CLASS_PRIMARY_POWER.getOrDefault(classId, 1);

				CharacterStats stats = new CharacterStats();
				stats.setHealth(rs.getInt("health"));
				stats.setMana(rs.getInt("power1"));

				Map<String, Integer> powers = new LinkedHashMap<>();
				addPowerIfNonZero(rs, powers, primaryPowerIdx);
				if (classId == 6) {
					addPowerIfNonZero(rs, powers, 6); // rune
				}

				if (!powers.isEmpty()) {
					stats.setStatExtensions(Map.of(NAMESPACE, powers));
				}

				return stats;
			}
		}
	}

	private void addPowerIfNonZero(ResultSet rs, Map<String, Integer> dest, int powerIndex) throws SQLException {
		int val = rs.getInt("power" + powerIndex);
		if (val > 0) {
			String name = TrinityCoreMaps.POWER_ID_TO_NAME.getOrDefault(powerIndex, "power" + powerIndex);
			dest.put(name, val);
		}
	}


	private List<Equipment> readEquipment(Connection conn, int guid) throws SQLException {
		String sql = "SELECT ci.slot, ii.itemEntry " + "FROM character_inventory ci "
				+ "JOIN item_instance ii ON ci.item = ii.guid " + "WHERE ci.guid = ? AND ci.bag = 0 AND ci.slot < 19 "
				+ "ORDER BY ci.slot";

		List<Equipment> equipment = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int slot = rs.getInt("slot");
					int itemEntry = rs.getInt("itemEntry");

					if (slot >= TrinityCoreMaps.SLOT_NAMES.length)
						continue;

					Equipment eq = new Equipment(TrinityCoreMaps.SLOT_NAMES[slot], NAMESPACE, String.valueOf(itemEntry),
							null 
					);
					equipment.add(eq);
				}
			}
		}

		return equipment;
	}

	private InventorySplit readInventorySplit(Connection conn, int guid) throws SQLException {
		String sql = "SELECT ci.bag, ci.slot, ci.item, ii.itemEntry, ii.count AS itemCount, ii.durability "
				+ "FROM character_inventory ci "
				+ "JOIN item_instance ii ON ci.item = ii.guid "
				+ "WHERE ci.guid = ? "
				+ "ORDER BY ci.bag, ci.slot";

		List<ItemRow> rows = new ArrayList<>();
		Map<Long, Integer> topLevelSlotsByItemGuid = new HashMap<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					ItemRow row = new ItemRow(
							rs.getInt("bag"),
							rs.getInt("slot"),
							rs.getLong("item"),
							rs.getInt("itemEntry"),
							rs.getInt("itemCount"),
							rs.getInt("durability")
					);
					rows.add(row);

					if (row.bag() == 0) {
						topLevelSlotsByItemGuid.put(row.itemGuid(), row.slot());
					}
				}
			}
		}

		List<InventoryItem> inventory = new ArrayList<>();
		List<InventoryItem> bank = new ArrayList<>();

		for (ItemRow row : rows) {
			if (row.bag() == 0 && isEquipmentSlot(row.slot())) {
				continue;
			}

			String location = classifyInventoryLocation(row, topLevelSlotsByItemGuid);
			InventoryItem item = new InventoryItem(
					location,
					row.bag(),
					row.slot(),
					row.itemGuid(),
					NAMESPACE,
					String.valueOf(row.itemEntry()),
					row.count(),
					row.durability()
			);

			if ("bank".equals(location)) {
				bank.add(item);
			} else {
				inventory.add(item);
			}
		}

		return new InventorySplit(inventory, bank);
	}

	private boolean isEquipmentSlot(int slot) {
		return slot >= 0 && slot < 19;
	}

	private String classifyInventoryLocation(ItemRow row, Map<Long, Integer> topLevelSlotsByItemGuid) {
		int owningSlot = row.slot();

		if (row.bag() != 0) {
			owningSlot = topLevelSlotsByItemGuid.getOrDefault((long) row.bag(), -1);
		}

		if (owningSlot >= 39 && owningSlot <= 74) {
			return "bank";
		}

		return "inventory";
	}

	private record ItemRow(int bag, int slot, long itemGuid, int itemEntry, int count, int durability) {
	}

	private record InventorySplit(List<InventoryItem> inventory, List<InventoryItem> bank) {
	}


	private Currency readCurrency(Connection conn, int guid) throws SQLException {
		String sql = "SELECT money FROM characters WHERE guid = ?";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next())
					return null;

				long copper = rs.getLong("money");
				long gold = copper / 10000;
				long silver = (copper % 10000) / 100;
				long rem = copper % 100;

				Map<String, Long> values = new LinkedHashMap<>();
				values.put("copper", copper);
				values.put("gold", gold);
				values.put("silver", silver);
				values.put("copper_remainder", rem);

				return new Currency(NAMESPACE, values);
			}
		}
	}


	private Progression readProgression(Connection conn, int guid) throws SQLException {
		Progression progression = new Progression();

		progression.setSkills(Map.of(NAMESPACE, readSkills(conn, guid)));
		progression.setReputation(Map.of(NAMESPACE, readReputation(conn, guid)));
		progression.setQuestsCompleted(Map.of(NAMESPACE, readCompletedQuests(conn, guid)));
		progression.setTalents(Map.of(NAMESPACE, readTalents(conn, guid)));

		return progression;
	}

	private Map<String, Integer> readSkills(Connection conn, int guid) throws SQLException {
		
		String sql = "SELECT skill, value FROM character_skills WHERE guid = ?";
		Map<String, Integer> skills = new LinkedHashMap<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					
					skills.put(String.valueOf(rs.getInt("skill")), rs.getInt("value"));
				}
			}
		}

		return skills;
	}

	private Map<String, Integer> readReputation(Connection conn, int guid) throws SQLException {
		
		String sql = "SELECT faction, standing FROM character_reputation WHERE guid = ?";
		Map<String, Integer> reputation = new LinkedHashMap<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					reputation.put(String.valueOf(rs.getInt("faction")), rs.getInt("standing"));
				}
			}
		}

		return reputation;
	}

	private List<Integer> readCompletedQuests(Connection conn, int guid) throws SQLException {
		
		String sql = "SELECT quest FROM character_queststatus_rewarded WHERE guid = ?";
		List<Integer> quests = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					quests.add(rs.getInt("quest"));
				}
			}
		}

		return quests;
	}

	private List<Integer> readTalents(Connection conn, int guid) throws SQLException {

		String sql = "SELECT spell FROM character_talent WHERE guid = ? ORDER BY spell";
		List<Integer> talents = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					talents.add(rs.getInt("spell"));
				}
			}
		}

		return talents;
	}


	 // Guardians, critters, and possessed units are excluded
	protected List<Pet> readPets(Connection conn, int guid) throws SQLException {
		String sql = "SELECT entry, level, name, slot, PetType, curhealth, curmana, curhappiness"
				+ " FROM character_pet"
				+ " WHERE owner = ? AND PetType IN (0, 1)"
				+ " ORDER BY slot";

		List<Pet> pets = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int    petTypeId  = rs.getInt("PetType");
					String petTypeRef = TrinityCoreMaps.PET_TYPE_TO_REF.getOrDefault(petTypeId, "unknown");
					String slotLabel  = TrinityCoreMaps.petSlotLabel(rs.getInt("slot"));

					Pet pet = new Pet(
							rs.getString("name"),
							NAMESPACE,
							String.valueOf(rs.getInt("entry")),
							rs.getInt("level"),
							petTypeRef,
							slotLabel,
							rs.getInt("curhealth"),
							rs.getInt("curmana"),
							rs.getInt("curhappiness")
					);
					pets.add(pet);
				}
			}
		}

		return pets;
	}

	protected HearthstoneLocation readHearthstone(Connection conn, int guid) throws SQLException {
		String sql = "SELECT mapId, zoneId, posX, posY, posZ FROM character_homebind WHERE guid = ?";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					int    mapId    = rs.getInt("mapId");
					int    zoneId   = rs.getInt("zoneId");
					String zoneName = TrinityCoreMaps.ZONE_ID_TO_NAME.get(zoneId);

					return new HearthstoneLocation(
							NAMESPACE,
							mapId,
							zoneId,
							zoneName,
							rs.getFloat("posX"),
							rs.getFloat("posY"),
							rs.getFloat("posZ")
					);
				}
			}
		}

		return null;
	}

	private void updateFromPayload(int guid, CharacterPayload payload) throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			try {
				if (payload.getIdentity() != null) {
					updateIdentity(conn, guid, payload.getIdentity());
				}
				if (payload.getCurrency() != null) {
					updateCurrency(conn, guid, payload.getCurrency());
				}
				if (payload.getHearthstone() != null) {
					updateHearthstone(conn, guid, payload.getHearthstone());
				}
				if (payload.getProgression() != null) {
					updateProgression(conn, guid, payload.getProgression());
				}
				if (payload.getPets() != null && !payload.getPets().isEmpty()) {
					updatePets(conn, guid, payload.getPets());
				}
				// Import inventory, bank, and equipment — full replacement
				List<InventoryItem> inv  = payload.getInventory();
				List<InventoryItem> bank = payload.getBank();
				List<Equipment>     equip = payload.getEquipment();
				if (inv != null || bank != null || equip != null) {
					importInventory(conn, guid, inv, bank, equip);
				}
				conn.commit();
				log.info("Import applied to guid=" + guid);
			} catch (SQLException e) {
				conn.rollback();
				throw e;
			}
		}
	}

	private void updateIdentity(Connection conn, int guid, CharacterIdentity identity) throws SQLException {
		String sql = "UPDATE characters SET level = ? WHERE guid = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, identity.getLevel());
			ps.setInt(2, guid);
			ps.executeUpdate();
		}
	}

	private void updateCurrency(Connection conn, int guid, Currency currency) throws SQLException {
		if (!getNamespace().equals(currency.getNamespace()))
			return;
		Map<String, Long> values = currency.getValues();
		if (values == null || !values.containsKey("copper"))
			return;

		String sql = "UPDATE characters SET money = ? WHERE guid = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, values.get("copper"));
			ps.setInt(2, guid);
			ps.executeUpdate();
		}
	}

	private void updateHearthstone(Connection conn, int guid, HearthstoneLocation hs) throws SQLException {
		String sql = "INSERT INTO character_homebind (guid, mapId, zoneId, posX, posY, posZ)"
				+ " VALUES (?, ?, ?, ?, ?, ?)"
				+ " ON DUPLICATE KEY UPDATE"
				+ "   mapId=VALUES(mapId), zoneId=VALUES(zoneId),"
				+ "   posX=VALUES(posX),   posY=VALUES(posY),   posZ=VALUES(posZ)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			ps.setInt(2, hs.getMapId());
			ps.setInt(3, hs.getZoneId());
			ps.setFloat(4, hs.getX());
			ps.setFloat(5, hs.getY());
			ps.setFloat(6, hs.getZ());
			ps.executeUpdate();
		}
	}

	private void updateProgression(Connection conn, int guid, Progression progression) throws SQLException {
		String ns = getNamespace();

		Map<String, Map<String, Integer>> allSkills = progression.getSkills();
		if (allSkills != null) {
			Map<String, Integer> skills = allSkills.get(ns);
			if (skills != null && !skills.isEmpty())
				updateSkills(conn, guid, skills);
		}

		Map<String, Map<String, Integer>> allRep = progression.getReputation();
		if (allRep != null) {
			Map<String, Integer> rep = allRep.get(ns);
			if (rep != null && !rep.isEmpty())
				updateReputation(conn, guid, rep);
		}

		Map<String, List<Integer>> allQuests = progression.getQuestsCompleted();
		if (allQuests != null) {
			List<Integer> quests = allQuests.get(ns);
			if (quests != null && !quests.isEmpty())
				updateQuestsCompleted(conn, guid, quests);
		}

		Map<String, List<Integer>> allTalents = progression.getTalents();
		if (allTalents != null) {
			List<Integer> talents = allTalents.get(ns);
			if (talents != null && !talents.isEmpty())
				updateTalents(conn, guid, talents);
		}
	}

	private void updateSkills(Connection conn, int guid, Map<String, Integer> skills) throws SQLException {
		String sql = "INSERT INTO character_skills (guid, skill, value, max)"
				+ " VALUES (?, ?, ?, ?)"
				+ " ON DUPLICATE KEY UPDATE value=VALUES(value), max=VALUES(max)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (Map.Entry<String, Integer> entry : skills.entrySet()) {
				try {
					int skillId = Integer.parseInt(entry.getKey());
					int value   = entry.getValue();
					ps.setInt(1, guid);
					ps.setInt(2, skillId);
					ps.setInt(3, value);
					ps.setInt(4, value);
					ps.addBatch();
				} catch (NumberFormatException ignored) {}
			}
			ps.executeBatch();
		}
	}

	private void updateReputation(Connection conn, int guid, Map<String, Integer> reputation) throws SQLException {
		String sql = "INSERT INTO character_reputation (guid, faction, standing, flags)"
				+ " VALUES (?, ?, ?, 0)"
				+ " ON DUPLICATE KEY UPDATE standing=VALUES(standing)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (Map.Entry<String, Integer> entry : reputation.entrySet()) {
				try {
					int factionId = Integer.parseInt(entry.getKey());
					int standing  = entry.getValue();
					ps.setInt(1, guid);
					ps.setInt(2, factionId);
					ps.setInt(3, standing);
					ps.addBatch();
				} catch (NumberFormatException ignored) {}
			}
			ps.executeBatch();
		}
	}

	private void updateQuestsCompleted(Connection conn, int guid, List<Integer> quests) throws SQLException {
		String sql = "INSERT IGNORE INTO character_queststatus_rewarded (guid, quest) VALUES (?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (int questId : quests) {
				ps.setInt(1, guid);
				ps.setInt(2, questId);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	private void updateTalents(Connection conn, int guid, List<Integer> talentSpells) throws SQLException {
		try (PreparedStatement del = conn.prepareStatement(
				"DELETE FROM character_talent WHERE guid = ? AND specMask = 1")) {
			del.setInt(1, guid);
			del.executeUpdate();
		}
		String sql = "INSERT IGNORE INTO character_talent (guid, spell, specMask) VALUES (?, ?, 1)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (int spellId : talentSpells) {
				ps.setInt(1, guid);
				ps.setInt(2, spellId);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	private void updatePets(Connection conn, int guid, List<Pet> pets) throws SQLException {
		int nextId;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT COALESCE(MAX(id), 0) + 1 FROM character_pet");
			 ResultSet rs = ps.executeQuery()) {
			rs.next();
			nextId = rs.getInt(1);
		}

		for (Pet pet : pets) {
			String typeRef = pet.getPetType();
			if (!"hunter".equals(typeRef) && !"warlock".equals(typeRef)) continue;

			int creatureEntry;
			try { creatureEntry = Integer.parseInt(pet.getCreatureRef()); }
			catch (NumberFormatException ignored) { continue; }

			int petTypeId = "hunter".equals(typeRef) ? 1 : 0;
			int slotId    = resolveSlot(pet.getSlot());

			try (PreparedStatement chk = conn.prepareStatement(
					"SELECT id FROM character_pet WHERE owner=? AND entry=? AND PetType=? LIMIT 1")) {
				chk.setInt(1, guid);
				chk.setInt(2, creatureEntry);
				chk.setInt(3, petTypeId);
				try (ResultSet rs = chk.executeQuery()) {
					if (rs.next()) {
						int existingId = rs.getInt("id");
						try (PreparedStatement upd = conn.prepareStatement(
								"UPDATE character_pet SET name=?, level=?, slot=?,"
								+ " curhealth=?, curmana=?, curhappiness=?, savetime=UNIX_TIMESTAMP()"
								+ " WHERE id=?")) {
							upd.setString(1, pet.getName());
							upd.setInt(2, pet.getLevel());
							upd.setInt(3, slotId);
							upd.setInt(4, pet.getCurrentHealth());
							upd.setInt(5, pet.getCurrentMana());
							upd.setInt(6, pet.getHappiness());
							upd.setInt(7, existingId);
							upd.executeUpdate();
						}
					} else {
						try (PreparedStatement ins = conn.prepareStatement(
								"INSERT INTO character_pet"
								+ " (id, entry, owner, level, name, slot, PetType,"
								+ "  curhealth, curmana, curhappiness, renamed, savetime)"
								+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UNIX_TIMESTAMP())")) {
							ins.setInt(1, nextId++);
							ins.setInt(2, creatureEntry);
							ins.setInt(3, guid);
							ins.setInt(4, pet.getLevel());
							ins.setString(5, pet.getName());
							ins.setInt(6, slotId);
							ins.setInt(7, petTypeId);
							ins.setInt(8, pet.getCurrentHealth());
							ins.setInt(9, pet.getCurrentMana());
							ins.setInt(10, pet.getHappiness());
							ins.executeUpdate();
						}
					}
				}
			}
		}
	}

	private void importInventory(Connection conn, int guid,
			List<InventoryItem> inventory,
			List<InventoryItem> bank,
			List<Equipment> equipment) throws SQLException {

		List<Long> existingGuids = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT item FROM character_inventory WHERE guid = ?")) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) existingGuids.add(rs.getLong(1));
			}
		}
		try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM character_inventory WHERE guid = ?")) {
			ps.setInt(1, guid);
			ps.executeUpdate();
		}
		if (!existingGuids.isEmpty()) {
			StringBuilder inClause = new StringBuilder();
			for (int i = 0; i < existingGuids.size(); i++) {
				if (i > 0) inClause.append(',');
				inClause.append(existingGuids.get(i));
			}
			try (Statement st = conn.createStatement()) {
				st.executeUpdate("DELETE FROM item_instance WHERE guid IN (" + inClause + ")");
			}
		}

		long nextGuid;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT COALESCE(MAX(guid), 0) + 1 FROM item_instance");
			 ResultSet rs = ps.executeQuery()) {
			rs.next();
			nextGuid = rs.getLong(1);
		}

		List<InventoryItem> allItems = new ArrayList<>();
		if (inventory != null) allItems.addAll(inventory);
		if (bank != null)      allItems.addAll(bank);

		Map<Long, Long> sourceToNewGuid = new HashMap<>();
		for (InventoryItem item : allItems) {
			long srcGuid = item.getItemGuid();
			if (srcGuid != 0) {
				sourceToNewGuid.put(srcGuid, nextGuid++);
			}
		}

		Map<String, Integer> slotNameToId = new HashMap<>();
		for (int i = 0; i < TrinityCoreMaps.SLOT_NAMES.length; i++) {
			slotNameToId.put(TrinityCoreMaps.SLOT_NAMES[i], i);
		}
		if (equipment != null) {
			for (Equipment eq : equipment) {
				if (!getNamespace().equals(eq.getNamespace())) continue;
				Integer slotId = slotNameToId.get(eq.getSlot());
				if (slotId == null) continue;
				int itemEntry;
				try { itemEntry = Integer.parseInt(eq.getRefId()); }
				catch (NumberFormatException ignored) { continue; }
				long newGuid = nextGuid++;
				try {
					insertItemInstance(conn, newGuid, itemEntry, guid, 1, 0);
					insertInventoryEntry(conn, guid, 0L, slotId, newGuid);
				} catch (SQLException e) {
					log.warning("importInventory: skipping equipment slot=" + eq.getSlot()
							+ " entry=" + itemEntry + " — " + e.getMessage());
				}
			}
		}

		for (InventoryItem item : allItems) {
			if (item.getBag() != 0) continue;
			long srcGuid = item.getItemGuid();
			Long newGuid = sourceToNewGuid.get(srcGuid);
			if (newGuid == null) continue;
			int itemEntry;
			try { itemEntry = Integer.parseInt(item.getRefId()); }
			catch (NumberFormatException ignored) { continue; }
			try {
				insertItemInstance(conn, newGuid, itemEntry, guid, item.getCount(), item.getDurability());
				insertInventoryEntry(conn, guid, 0L, item.getSlot(), newGuid);
			} catch (SQLException e) {
				log.warning("importInventory: skipping top-level item entry=" + itemEntry
						+ " slot=" + item.getSlot() + " — " + e.getMessage());
			}
		}

		for (InventoryItem item : allItems) {
			if (item.getBag() == 0) continue;
			Long newBagGuid = sourceToNewGuid.get((long) item.getBag());
			if (newBagGuid == null) {
				log.warning("importInventory: no newGuid for bag=" + item.getBag()
						+ " — skipping slot " + item.getSlot());
				continue;
			}
			long srcGuid = item.getItemGuid();
			Long newGuid = sourceToNewGuid.get(srcGuid);
			if (newGuid == null) continue;
			int itemEntry;
			try { itemEntry = Integer.parseInt(item.getRefId()); }
			catch (NumberFormatException ignored) { continue; }
			try {
				insertItemInstance(conn, newGuid, itemEntry, guid, item.getCount(), item.getDurability());
				insertInventoryEntry(conn, guid, newBagGuid, item.getSlot(), newGuid);
			} catch (SQLException e) {
				log.warning("importInventory: skipping bag content entry=" + itemEntry
						+ " bag=" + item.getBag() + " slot=" + item.getSlot() + " — " + e.getMessage());
			}
		}

		log.info("importInventory complete — guid=" + guid
				+ " equipment=" + (equipment != null ? equipment.size() : 0)
				+ " inventory=" + (inventory != null ? inventory.size() : 0)
				+ " bank=" + (bank != null ? bank.size() : 0));
	}

	private void insertItemInstance(Connection conn, long itemGuid, int itemEntry,
			int ownerGuid, int count, int durability) throws SQLException {
		final String EMPTY_ENCHANTMENTS =
				"0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 ";

		String sql = "INSERT INTO item_instance"
				+ " (guid, itemEntry, owner_guid, creatorGuid, giftCreatorGuid,"
				+ "  count, duration, charges, flags, enchantments,"
				+ "  randomPropertyId, durability, playedTime, `text`)"
				+ " VALUES (?, ?, ?, 0, 0, ?, 0, '', 0, ?, 0, ?, 0, '')";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, itemGuid);
			ps.setInt(2, itemEntry);
			ps.setInt(3, ownerGuid);
			ps.setInt(4, count);
			ps.setString(5, EMPTY_ENCHANTMENTS);
			ps.setInt(6, durability);
			ps.executeUpdate();
		}
	}

	private void insertInventoryEntry(Connection conn, int charGuid, long bagGuid,
			int slot, long itemGuid) throws SQLException {
		String sql = "INSERT INTO character_inventory (guid, bag, slot, item) VALUES (?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, charGuid);
			ps.setLong(2, bagGuid);
			ps.setInt(3, slot);
			ps.setLong(4, itemGuid);
			ps.executeUpdate();
		}
	}

	private int resolveSlot(String slotLabel) {
		if (slotLabel == null || "active".equals(slotLabel)) return 0;
		if (slotLabel.startsWith("stable_")) {
			try { return Integer.parseInt(slotLabel.substring(7)); }
			catch (NumberFormatException ignored) {}
		}
		return 0;
	}

	private void ensureMappingTable() {
		String sql = "CREATE TABLE IF NOT EXISTS registry_character_map ("
				+ "  registry_uuid VARCHAR(36) NOT NULL PRIMARY KEY," + "  char_guid     INT UNSIGNED NOT NULL,"
				+ "  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP" + ")";
		try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
			st.execute(sql);
			log.info("registry_character_map table ready");
		} catch (SQLException e) {
			log.severe("Failed to create mapping table: " + e.getMessage());
		}
	}

	private Integer resolveGuid(String registryUuid) {
		String sql = "SELECT char_guid FROM registry_character_map WHERE registry_uuid = ?";
		try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, registryUuid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next())
					return rs.getInt("char_guid");
			}
		} catch (SQLException e) {
			log.severe("Failed to resolve GUID for " + registryUuid + ": " + e.getMessage());
		}
		return null;
	}

	public void registerMapping(String registryUuid, int charGuid) {
		String sql = "INSERT IGNORE INTO registry_character_map (registry_uuid, char_guid) VALUES (?, ?)";
		try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, registryUuid);
			ps.setInt(2, charGuid);
			ps.executeUpdate();
			log.info("Mapped registry UUID " + registryUuid + " → local guid " + charGuid);
		} catch (SQLException e) {
			log.severe("Failed to register mapping: " + e.getMessage());
		}
	}


	private String capitalize(String s) {
		if (s == null || s.isEmpty())
			return s;
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
	}

	public void close() {
		dataSource.close();
	}
}
