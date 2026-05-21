package org.registryagent.adapter.trinitycore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.registryagent.adapter.ServerAdapter;
import org.registryagent.model.*;
import org.registryagent.model.Currency;
import org.registryagent.model.HearthstoneLocation;
import org.registryagent.model.Pet;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.Base64;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TrinityCoreAdapter implements ServerAdapter {

	private static final Logger log = Logger.getLogger(TrinityCoreAdapter.class.getName());

	public static final String NAMESPACE = "wow_wotlk_3.3.5a";

	private final HikariDataSource dataSource;

	private HikariDataSource worldDataSource;

	private int customItemThreshold = 100_000;

	private final ObjectMapper blobMapper = new ObjectMapper();

	public void setCustomItemThreshold(int customItemThreshold) {
		this.customItemThreshold = customItemThreshold;
	}

	public void setWorldDatabase(String jdbcUrl, String username, String password) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(jdbcUrl);
		config.setUsername(username);
		config.setPassword(password);
		config.setMaximumPoolSize(5);
		config.setConnectionTimeout(5000);
		config.setPoolName("trinitycore-world");
		this.worldDataSource = new HikariDataSource(config);
		log.info("World database configured: " + jdbcUrl);
	}

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
			log.warning("writeCharacter: no registry_character_map entry for " + characterId
					+ " - character has never authenticated on this server");
			return false;
		}
		if (!characterExists(guid)) {
			log.warning("writeCharacter: registry_character_map maps " + characterId + " → guid=" + guid
					+ " but no row exists in characters table"
					+ " - the character may have been deleted after its last login");
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

	private boolean characterExists(int guid) {
		String sql = "SELECT 1 FROM characters WHERE guid = ?";
		try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			log.severe("characterExists check failed for guid=" + guid + ": " + e.getMessage());
			return false;
		}
	}

	private boolean isOnline(int guid) {
		String sql = "SELECT online FROM characters WHERE guid = ?";
		try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt("online") != 0;
			}
		} catch (SQLException e) {
			log.warning("isOnline check failed for guid=" + guid + ": " + e.getMessage());
			return false;
		}
	}

	@Override
	public boolean importCharacter(String registryUuid, int accountId, CharacterPayload payload) {
		Integer guid = resolveGuid(registryUuid);

		if (guid != null && characterExists(guid)) {
			if (isOnline(guid)) {
				log.warning("importCharacter: character guid=" + guid + " is currently online"
						+ " - import refused to prevent worldserver cache desync."
						+ " The player must log out before importing.");
				return false;
			}
			log.info("importCharacter: updating existing character guid=" + guid + " for registryUuid=" + registryUuid);
			try {
				updateFromPayload(guid, payload);
				return true;
			} catch (SQLException e) {
				log.severe("importCharacter: DB error updating guid=" + guid + ": " + e.getMessage());
				return false;
			}
		}

		if (accountId <= 0) {
			log.warning("importCharacter: " + registryUuid
					+ " has no local character and no accountId was provided - cannot auto-create."
					+ " Supply an Account ID to create the character automatically.");
			return false;
		}

		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			try {
				int newGuid = createCharacterRow(conn, accountId, payload);

				String mapSql = "INSERT INTO registry_character_map" + " (registry_uuid, char_guid) VALUES (?, ?)"
						+ " ON DUPLICATE KEY UPDATE char_guid = VALUES(char_guid)";
				try (PreparedStatement mp = conn.prepareStatement(mapSql)) {
					mp.setString(1, registryUuid);
					mp.setInt(2, newGuid);
					mp.executeUpdate();
				}

				List<Integer> payloadSpells = payload.getProgression() != null
						? payload.getProgression().getSpellsFor(getNamespace()) : null;
				if ((payloadSpells == null || payloadSpells.isEmpty()) && payload.getIdentity() != null) {
					seedStartingSpells(conn, newGuid, payload.getIdentity());
				}

				applyPayload(conn, newGuid, payload);
				conn.commit();

				log.info("importCharacter: created character guid=" + newGuid + " account=" + accountId
						+ " registryUuid=" + registryUuid);
				return true;

			} catch (SQLException e) {
				conn.rollback();
				log.severe("importCharacter: failed to create character for " + registryUuid + ": " + e.getMessage());
				return false;
			}
		} catch (SQLException e) {
			log.severe("importCharacter: connection error for " + registryUuid + ": " + e.getMessage());
			return false;
		}
	}

	private static final Set<Integer> ALLIANCE_RACES = Set.of(1, 3, 4, 7, 11);

	private static final float[] ALLIANCE_START = { 0, -8833.37f, 628.62f, 94.00f, 5.31f, 1519 };
	private static final float[] HORDE_START = { 1, 1676.35f, 1677.45f, 121.67f, 2.70f, 1637 };

	private float[] startPosition(int raceId) {
		return ALLIANCE_RACES.contains(raceId) ? ALLIANCE_START : HORDE_START;
	}

	private int createCharacterRow(Connection conn, int accountId, CharacterPayload payload) throws SQLException {

		CharacterIdentity id = payload.getIdentity();
		if (id == null)
			throw new SQLException("Cannot create character: payload has no identity section");

		int raceId = resolveRaceId(id);
		int classId = resolveClassId(id);
		int genderId = TrinityCoreMaps.GENDER_REF_TO_ID.getOrDefault(id.getGender(), 0);

		long money = 0L;
		if (payload.getCurrency() != null && payload.getCurrency().getValues() != null) {
			Long copper = payload.getCurrency().getValues().get("copper");
			if (copper != null)
				money = copper;
		}

		float[] pos = startPosition(raceId);

		String emptyEquipCache = "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0";

		int newGuid;
		try (Statement st = conn.createStatement();
				ResultSet maxRs = st.executeQuery("SELECT IFNULL(MAX(guid), 0) + 1 FROM characters")) {
			if (!maxRs.next())
				throw new SQLException("Could not determine next character GUID");
			newGuid = maxRs.getInt(1);
		}

		String sql = "INSERT INTO characters (" + "  guid," + "  account, name, race, class, gender, level, xp, money,"
				+ "  skin, face, hairStyle, hairColor, facialStyle," + "  bankSlots, restState, playerFlags,"
				+ "  map, instance_id," + "  position_x, position_y, position_z, orientation,"
				+ "  taximask, online, cinematic, totaltime, leveltime,"
				+ "  logout_time, is_logout_resting, rest_bonus," + "  resettalents_cost, resettalents_time,"
				+ "  trans_x, trans_y, trans_z, trans_o, transguid,"
				+ "  extra_flags, stable_slots, at_login, zone, death_expire_time,"
				+ "  taxi_path, arenaPoints, totalHonorPoints, todayHonorPoints,"
				+ "  yesterdayHonorPoints, totalKills, todayKills, yesterdayKills,"
				+ "  chosenTitle, knownCurrencies, watchedFaction, drunk,"
				+ "  health, power1, power2, power3, power4, power5, power6, power7,"
				+ "  talentGroupsCount, activeTalentGroup,"
				+ "  exploredZones, equipmentCache, ammoId, knownTitles, actionBars, grantableLevels,"
				+ "  latency, innTriggerId" + ") VALUES (" + "  ?,?,?,?,?,?,?,0,?," + "  0,0,0,0,0," + "  0,1,0,"
				+ "  ?,0," + "  ?,?,?,?," + "  '',0,0,0,0," + "  0,0,0," + "  0,0," + "  0,0,0,0,0," + "  0,0,0,?,0,"
				+ "  '',0,0,0," + "  0,0,0,0," + "  0,0,0,0," + "  1,0,0,0,0,0,0,0," + "  1,0," + "  '',?,0,'',0,0,"
				+ "  0,0" + ")";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int p = 1;
			ps.setInt(p++, newGuid);
			ps.setInt(p++, accountId);
			ps.setString(p++, id.getName());
			ps.setInt(p++, raceId);
			ps.setInt(p++, classId);
			ps.setInt(p++, genderId);
			ps.setInt(p++, id.getLevel());
			ps.setLong(p++, money);
			ps.setInt(p++, (int) pos[0]); // map
			ps.setFloat(p++, pos[1]); // position_x
			ps.setFloat(p++, pos[2]); // position_y
			ps.setFloat(p++, pos[3]); // position_z
			ps.setFloat(p++, pos[4]); // orientation
			ps.setInt(p++, (int) pos[5]); // zone
			ps.setString(p++, emptyEquipCache);
			ps.executeUpdate();
		}

		return newGuid;
	}

	private int resolveRaceId(CharacterIdentity id) throws SQLException {
		String ref = id.getRaceRef();
		if (ref == null)
			throw new SQLException("Missing raceRef in identity payload");
		Integer id2 = TrinityCoreMaps.RACE_REF_TO_ID.get(ref);
		if (id2 != null)
			return id2;
		try {
			return Integer.parseInt(ref);
		} catch (NumberFormatException ignored) {
		}
		throw new SQLException("Unknown raceRef '" + ref + "' - not in TrinityCoreMaps and not numeric");
	}

	private int resolveClassId(CharacterIdentity id) throws SQLException {
		String ref = id.getClassRef();
		if (ref == null)
			throw new SQLException("Missing classRef in identity payload");
		Integer id2 = TrinityCoreMaps.CLASS_REF_TO_ID.get(ref);
		if (id2 != null)
			return id2;
		try {
			return Integer.parseInt(ref);
		} catch (NumberFormatException ignored) {
		}
		throw new SQLException("Unknown classRef '" + ref + "' - not in TrinityCoreMaps and not numeric");
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
				identity.setClassNamespace(getNamespace());
				identity.setClassLabel(capitalize(TrinityCoreMaps.CLASS_ID_TO_REF.getOrDefault(classId, "Unknown")));

				identity.setRaceRef(TrinityCoreMaps.RACE_ID_TO_REF.getOrDefault(raceId, "unknown_" + raceId));
				identity.setRaceNamespace(getNamespace());
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
					addPowerIfNonZero(rs, powers, 6); // dk rune
				}

				if (!powers.isEmpty()) {
					stats.setStatExtensions(Map.of(getNamespace(), powers));
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
		String sql = "SELECT ci.slot, ii.itemEntry, ii.durability " + "FROM character_inventory ci "
				+ "JOIN item_instance ii ON ci.item = ii.guid " + "WHERE ci.guid = ? AND ci.bag = 0 AND ci.slot < 19 "
				+ "ORDER BY ci.slot";

		List<Equipment> equipment = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int slot = rs.getInt("slot");
					int itemEntry = rs.getInt("itemEntry");
					int durability = rs.getInt("durability");

					if (slot >= TrinityCoreMaps.SLOT_NAMES.length)
						continue;

					Equipment eq = new Equipment(TrinityCoreMaps.SLOT_NAMES[slot], getNamespace(),
							String.valueOf(itemEntry), null, durability);
					equipment.add(eq);
				}
			}
		}

		return equipment;
	}

	private InventorySplit readInventorySplit(Connection conn, int guid) throws SQLException {
		String sql = "SELECT ci.bag, ci.slot, ci.item, ii.itemEntry, ii.count AS itemCount, ii.durability, ii.text "
				+ "FROM character_inventory ci " + "JOIN item_instance ii ON ci.item = ii.guid " + "WHERE ci.guid = ? "
				+ "ORDER BY ci.bag, ci.slot";

		List<ItemRow> rows = new ArrayList<>();
		Map<Long, Integer> topLevelSlotsByItemGuid = new HashMap<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String text = rs.getString("text");
					ItemRow row = new ItemRow(rs.getLong("bag"), rs.getInt("slot"), rs.getLong("item"),
							rs.getInt("itemEntry"), rs.getInt("itemCount"), rs.getInt("durability"),
							(text != null && !text.isEmpty()) ? text : null);
					rows.add(row);

					if (row.bag() == 0) {
						topLevelSlotsByItemGuid.put(row.itemGuid(), row.slot());
					}
				}
			}
		}

		Set<Integer> allEntries = rows.stream().map(ItemRow::itemEntry).collect(Collectors.toSet());
		Map<Integer, String> blobByEntry = buildTemplateBlobMap(allEntries);

		List<InventoryItem> inventory = new ArrayList<>();
		List<InventoryItem> bank = new ArrayList<>();

		for (ItemRow row : rows) {
			if (row.bag() == 0 && isEquipmentSlot(row.slot())) {
				continue;
			}

			String location = classifyInventoryLocation(row, topLevelSlotsByItemGuid);
			InventoryItem item = new InventoryItem(location, row.bag(), row.slot(), row.itemGuid(), getNamespace(),
					String.valueOf(row.itemEntry()), row.count(), row.durability(), blobByEntry.get(row.itemEntry()));
			item.setItemText(row.text());

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
			owningSlot = topLevelSlotsByItemGuid.getOrDefault(row.bag(), -1);
		}

		if (owningSlot >= 39 && owningSlot <= 74) {
			return "bank";
		}

		return "inventory";
	}

	private record ItemRow(long bag, int slot, long itemGuid, int itemEntry, int count, int durability, String text) {
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

				return new Currency(getNamespace(), values);
			}
		}
	}

	private Progression readProgression(Connection conn, int guid) throws SQLException {
		Progression progression = new Progression();

		progression.setSkills(Map.of(getNamespace(), readSkills(conn, guid)));
		progression.setReputation(Map.of(getNamespace(), readReputation(conn, guid)));
		progression.setQuestsCompleted(Map.of(getNamespace(), readCompletedQuests(conn, guid)));
		progression.setTalents(Map.of(getNamespace(), readTalents(conn, guid)));
		progression.setSpells(Map.of(getNamespace(), readSpells(conn, guid)));

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

	private List<Integer> readSpells(Connection conn, int guid) throws SQLException {

		String sql = "SELECT spell FROM character_spell WHERE guid = ? ORDER BY spell";
		List<Integer> spells = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					spells.add(rs.getInt("spell"));
				}
			}
		}

		return spells;
	}

	// Guardians, critters, and possessed units are excluded
	protected List<Pet> readPets(Connection conn, int guid) throws SQLException {
		String sql = "SELECT entry, level, name, slot, PetType, curhealth, curmana, curhappiness, modelid"
				+ " FROM character_pet WHERE owner = ? AND PetType IN (0, 1) ORDER BY slot";

		List<Pet> pets = new ArrayList<>();

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int petTypeId = rs.getInt("PetType");
					String petTypeRef = TrinityCoreMaps.PET_TYPE_TO_REF.getOrDefault(petTypeId, "unknown");
					String slotLabel = TrinityCoreMaps.petSlotLabel(rs.getInt("slot"));

					Pet pet = new Pet(rs.getString("name"), getNamespace(), String.valueOf(rs.getInt("entry")),
							rs.getInt("level"), petTypeRef, slotLabel, rs.getInt("curhealth"), rs.getInt("curmana"),
							rs.getInt("curhappiness"), rs.getInt("modelid"));
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
					int mapId = rs.getInt("mapId");
					int zoneId = rs.getInt("zoneId");
					String zoneName = TrinityCoreMaps.ZONE_ID_TO_NAME.get(zoneId);

					return new HearthstoneLocation(NAMESPACE, mapId, zoneId, zoneName, rs.getFloat("posX"),
							rs.getFloat("posY"), rs.getFloat("posZ"));
				}
			}
		}

		return null;
	}

	private void updateFromPayload(int guid, CharacterPayload payload) throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			try {
				applyPayload(conn, guid, payload);
				conn.commit();
				log.info("Import applied to guid=" + guid);
			} catch (SQLException e) {
				conn.rollback();
				throw e;
			}
		}
	}

	private void applyPayload(Connection conn, int guid, CharacterPayload payload) throws SQLException {
		if (payload.getIdentity() != null) {
			updateIdentity(conn, guid, payload.getIdentity());
		}
		if (payload.getStats() != null) {
			updateStats(conn, guid, payload.getStats());
		}
		if (payload.getCurrency() != null) {
			updateCurrency(conn, guid, payload.getCurrency());
		}
		if (payload.getHearthstone() != null) {
			updateHearthstone(conn, guid, payload.getHearthstone());
		}
		if (payload.getIdentity() != null) {
			resetPositionToStart(conn, guid, payload.getIdentity(), payload.getHearthstone());
		}
		if (payload.getProgression() != null) {
			updateProgression(conn, guid, payload.getProgression());
		}
		if (payload.getPets() != null && !payload.getPets().isEmpty()) {
			updatePets(conn, guid, payload.getPets());
		}
		List<InventoryItem> inv = payload.getInventory();
		List<InventoryItem> bank = payload.getBank();
		List<Equipment> equip = payload.getEquipment();
		if (inv != null || bank != null || equip != null) {
			importInventory(conn, guid, inv, bank, equip);
		}
	}

	private void updateStats(Connection conn, int guid, CharacterStats stats) throws SQLException {
		int health = Math.max(1, stats.getHealth());
		int mana = Math.max(0, stats.getMana());
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE characters SET health = ?, power1 = ? WHERE guid = ?")) {
			ps.setInt(1, health);
			ps.setInt(2, mana);
			ps.setInt(3, guid);
			ps.executeUpdate();
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

	private static final Set<Integer> OPEN_WORLD_MAPS = Set.of(0, 1, 530, 571);

	private void resetPositionToStart(Connection conn, int guid, CharacterIdentity identity,
			HearthstoneLocation hearthstone) throws SQLException {

		if (hearthstone != null && OPEN_WORLD_MAPS.contains(hearthstone.getMapId())) {
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE characters SET map=?, position_x=?, position_y=?, position_z=?,"
					+ " orientation=0, zone=?, instance_id=0 WHERE guid=?")) {
				ps.setInt(1, hearthstone.getMapId());
				ps.setFloat(2, hearthstone.getX());
				ps.setFloat(3, hearthstone.getY());
				ps.setFloat(4, hearthstone.getZ());
				ps.setInt(5, hearthstone.getZoneId());
				ps.setInt(6, guid);
				ps.executeUpdate();
			}
			log.info("resetPosition: using hearthstone location map=" + hearthstone.getMapId()
					+ " zone=" + hearthstone.getZoneId() + " for guid=" + guid);
			return;
		}

		if (hearthstone != null) {
			log.warning("resetPosition: hearthstone map=" + hearthstone.getMapId()
					+ " is not an open-world map - falling back to racial start for guid=" + guid);
		}

		int raceId  = TrinityCoreMaps.RACE_REF_TO_ID.getOrDefault(identity.getRaceRef(), 0);
		int classId = TrinityCoreMaps.CLASS_REF_TO_ID.getOrDefault(identity.getClassRef(), 0);

		if (worldDataSource != null && raceId != 0 && classId != 0) {
			try (Connection wc = worldDataSource.getConnection();
					PreparedStatement ps = wc.prepareStatement(
							"SELECT map, position_x, position_y, position_z, orientation, zone"
							+ " FROM playercreateinfo WHERE race = ? AND class = ? LIMIT 1")) {
				ps.setInt(1, raceId);
				ps.setInt(2, classId);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						try (PreparedStatement upd = conn.prepareStatement(
								"UPDATE characters SET map=?, position_x=?, position_y=?, position_z=?,"
								+ " orientation=?, zone=?, instance_id=0 WHERE guid=?")) {
							upd.setInt(1, rs.getInt("map"));
							upd.setFloat(2, rs.getFloat("position_x"));
							upd.setFloat(3, rs.getFloat("position_y"));
							upd.setFloat(4, rs.getFloat("position_z"));
							upd.setFloat(5, rs.getFloat("orientation"));
							upd.setInt(6, rs.getInt("zone"));
							upd.setInt(7, guid);
							upd.executeUpdate();
						}
						return;
					}
				}
			} catch (SQLException e) {
				log.warning("resetPosition: playercreateinfo lookup failed - falling back to hardcoded: " + e.getMessage());
			}
		}

		float[] pos = startPosition(raceId);
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE characters SET map=?, position_x=?, position_y=?, position_z=?, orientation=?,"
				+ " zone=?, instance_id=0 WHERE guid=?")) {
			ps.setInt(1, (int) pos[0]);
			ps.setFloat(2, pos[1]);
			ps.setFloat(3, pos[2]);
			ps.setFloat(4, pos[3]);
			ps.setFloat(5, pos[4]);
			ps.setInt(6, (int) pos[5]);
			ps.setInt(7, guid);
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
				+ " VALUES (?, ?, ?, ?, ?, ?)" + " ON DUPLICATE KEY UPDATE"
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

		Map<String, List<Integer>> allSpells = progression.getSpells();
		if (allSpells != null) {
			List<Integer> spells = allSpells.get(ns);
			if (spells != null && !spells.isEmpty())
				updateSpells(conn, guid, spells);
		}
	}

	private void updateSpells(Connection conn, int guid, List<Integer> spells) throws SQLException {
		String sql = "INSERT IGNORE INTO character_spell (guid, spell, specMask) VALUES (?, ?, 255)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (int spellId : spells) {
				ps.setInt(1, guid);
				ps.setInt(2, spellId);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	private void seedStartingSpells(Connection conn, int guid, CharacterIdentity identity) {
		if (worldDataSource == null) {
			log.warning("seedStartingSpells: no world database configured - character may be missing starting spells");
			return;
		}
		int raceId  = TrinityCoreMaps.RACE_REF_TO_ID.getOrDefault(identity.getRaceRef(), 0);
		int classId = TrinityCoreMaps.CLASS_REF_TO_ID.getOrDefault(identity.getClassRef(), 0);
		if (raceId == 0 || classId == 0) {
			log.warning("seedStartingSpells: unknown race/class (" + identity.getRaceRef()
					+ "/" + identity.getClassRef() + ") - skipping spell seed");
			return;
		}
		try {
			List<Integer> spells = new ArrayList<>();
			try (Connection wc = worldDataSource.getConnection();
					PreparedStatement ps = wc.prepareStatement(
							"SELECT Spell FROM playercreateinfo_spell WHERE race = ? AND class = ?")) {
				ps.setInt(1, raceId);
				ps.setInt(2, classId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) spells.add(rs.getInt("Spell"));
				}
			}
			if (spells.isEmpty()) {
				log.warning("seedStartingSpells: no rows in playercreateinfo_spell for race="
						+ raceId + " class=" + classId + " - character may lack starting abilities");
				return;
			}
			try (PreparedStatement ins = conn.prepareStatement(
					"INSERT IGNORE INTO character_spell (guid, spell, specMask) VALUES (?, ?, 255)")) {
				for (int spell : spells) {
					ins.setInt(1, guid);
					ins.setInt(2, spell);
					ins.addBatch();
				}
				ins.executeBatch();
			}
			log.info("seedStartingSpells: seeded " + spells.size() + " spells for guid=" + guid
					+ " race=" + raceId + " class=" + classId);
		} catch (SQLException e) {
			log.warning("seedStartingSpells: failed for guid=" + guid + ": " + e.getMessage());
		}
	}

	private void updateSkills(Connection conn, int guid, Map<String, Integer> skills) throws SQLException {
		String sql = "INSERT INTO character_skills (guid, skill, value, max)" + " VALUES (?, ?, ?, ?)"
				+ " ON DUPLICATE KEY UPDATE value=VALUES(value), max=VALUES(max)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (Map.Entry<String, Integer> entry : skills.entrySet()) {
				try {
					int skillId = Integer.parseInt(entry.getKey());
					int value = entry.getValue();
					ps.setInt(1, guid);
					ps.setInt(2, skillId);
					ps.setInt(3, value);
					ps.setInt(4, value);
					ps.addBatch();
				} catch (NumberFormatException ignored) {
				}
			}
			ps.executeBatch();
		}
	}

	private void updateReputation(Connection conn, int guid, Map<String, Integer> reputation) throws SQLException {
		String sql = "INSERT INTO character_reputation (guid, faction, standing, flags)" + " VALUES (?, ?, ?, 0)"
				+ " ON DUPLICATE KEY UPDATE standing=VALUES(standing)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (Map.Entry<String, Integer> entry : reputation.entrySet()) {
				try {
					int factionId = Integer.parseInt(entry.getKey());
					int standing = entry.getValue();
					ps.setInt(1, guid);
					ps.setInt(2, factionId);
					ps.setInt(3, standing);
					ps.addBatch();
				} catch (NumberFormatException ignored) {
				}
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
		try (PreparedStatement del = conn
				.prepareStatement("DELETE FROM character_talent WHERE guid = ? AND specMask = 1")) {
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
		try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM character_pet");
				ResultSet rs = ps.executeQuery()) {
			rs.next();
			nextId = rs.getInt(1);
		}

		for (Pet pet : pets) {
			String typeRef = pet.getPetType();
			if (!"hunter".equals(typeRef) && !"warlock".equals(typeRef))
				continue;

			int creatureEntry;
			try {
				creatureEntry = Integer.parseInt(pet.getCreatureRef());
			} catch (NumberFormatException ignored) {
				continue;
			}

			int petTypeId = "hunter".equals(typeRef) ? 1 : 0;
			int slotId = resolveSlot(pet.getSlot());

			int displayId = 0;
			if (worldDataSource != null) {
				try (Connection worldConn = worldDataSource.getConnection();
						PreparedStatement disp = worldConn.prepareStatement(
								"SELECT CreatureDisplayID FROM creature_template_model WHERE CreatureID = ? ORDER BY Idx ASC LIMIT 1")) {
					disp.setInt(1, creatureEntry);
					try (ResultSet dispRs = disp.executeQuery()) {
						if (dispRs.next()) displayId = dispRs.getInt("CreatureDisplayID");
					}
				}
			}
			if (displayId == 0) {
				displayId = pet.getSourceModelId();
			}
			if (displayId == 0) {
				log.warning("updatePets: no display ID found for creature entry=" + creatureEntry
						+ " - skipping pet '" + pet.getName() + "'");
				continue;
			}

			try (PreparedStatement chk = conn
					.prepareStatement("SELECT id FROM character_pet WHERE owner=? AND entry=? AND PetType=? LIMIT 1")) {
				chk.setInt(1, guid);
				chk.setInt(2, creatureEntry);
				chk.setInt(3, petTypeId);
				try (ResultSet rs = chk.executeQuery()) {
					if (rs.next()) {
						int existingId = rs.getInt("id");
						try (PreparedStatement upd = conn
								.prepareStatement("UPDATE character_pet SET name=?, level=?, slot=?,"
										+ " modelid=?, curhealth=?, curmana=?, curhappiness=?,"
										+ " savetime=UNIX_TIMESTAMP() WHERE id=?")) {
							upd.setString(1, pet.getName());
							upd.setInt(2, pet.getLevel());
							upd.setInt(3, slotId);
							upd.setInt(4, displayId);
							upd.setInt(5, pet.getCurrentHealth());
							upd.setInt(6, pet.getCurrentMana());
							upd.setInt(7, pet.getHappiness());
							upd.setInt(8, existingId);
							upd.executeUpdate();
						}
					} else {
						try (PreparedStatement ins = conn.prepareStatement(
								"INSERT INTO character_pet (id, entry, owner, level, name, slot, PetType,"
										+ " modelid, curhealth, curmana, curhappiness, renamed, savetime)"
										+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UNIX_TIMESTAMP())")) {
							ins.setInt(1, nextId++);
							ins.setInt(2, creatureEntry);
							ins.setInt(3, guid);
							ins.setInt(4, pet.getLevel());
							ins.setString(5, pet.getName());
							ins.setInt(6, slotId);
							ins.setInt(7, petTypeId);
							ins.setInt(8, displayId);
							ins.setInt(9, pet.getCurrentHealth());
							ins.setInt(10, pet.getCurrentMana());
							ins.setInt(11, pet.getHappiness());
							ins.executeUpdate();
						}
					}
				}
			}
		}
	}

	private void importInventory(Connection conn, int guid, List<InventoryItem> inventory, List<InventoryItem> bank,
			List<Equipment> equipment) throws SQLException {

		List<Long> existingGuids = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement("SELECT item FROM character_inventory WHERE guid = ?")) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next())
					existingGuids.add(rs.getLong(1));
			}
		}
		try (PreparedStatement ps = conn.prepareStatement("DELETE FROM character_inventory WHERE guid = ?")) {
			ps.setInt(1, guid);
			ps.executeUpdate();
		}
		if (!existingGuids.isEmpty()) {
			StringBuilder inClause = new StringBuilder();
			for (int i = 0; i < existingGuids.size(); i++) {
				if (i > 0)
					inClause.append(',');
				inClause.append(existingGuids.get(i));
			}
			try (Statement st = conn.createStatement()) {
				st.executeUpdate("DELETE FROM item_instance WHERE guid IN (" + inClause + ")");
			}
		}

		long nextGuid;
		try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(guid), 0) + 1 FROM item_instance");
				ResultSet rs = ps.executeQuery()) {
			rs.next();
			nextGuid = rs.getLong(1);
		}

		List<InventoryItem> allItems = new ArrayList<>();
		if (inventory != null)
			allItems.addAll(inventory);
		if (bank != null)
			allItems.addAll(bank);

		// Slots 67-73 (bag=0) are bank bag container positions; bankSlots must be at
		// least (highestOccupiedSlot - 66) or AzerothCore will evict the bags on login.
		int bankSlotsNeeded = 0;
		for (InventoryItem item : allItems) {
			if (item.getBag() == 0 && item.getSlot() >= 67 && item.getSlot() <= 73) {
				bankSlotsNeeded = Math.max(bankSlotsNeeded, item.getSlot() - 66);
			}
		}

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

		int[] equipCacheEntries = new int[19];

		if (equipment != null) {
			for (Equipment eq : equipment) {

				if (eq.getNamespace() == null)
					continue;
				Integer slotId = slotNameToId.get(eq.getSlot());
				if (slotId == null)
					continue;
				int itemEntry;
				try {
					itemEntry = Integer.parseInt(eq.getRefId());
				} catch (NumberFormatException ignored) {
					continue;
				}
				if (!ensureItemTemplateExists(conn, itemEntry, null)) {
					log.warning("importInventory: skipping equipment slot=" + eq.getSlot() + " entry=" + itemEntry
							+ " - not in item_template and no blob available");
					continue;
				}
				long newGuid = nextGuid++;
				try {
					insertItemInstance(conn, newGuid, itemEntry, guid, 1, eq.getDurability(), null);
					insertInventoryEntry(conn, guid, 0L, slotId, newGuid);
					equipCacheEntries[slotId] = itemEntry;
				} catch (SQLException e) {
					log.warning("importInventory: skipping equipment slot=" + eq.getSlot() + " entry=" + itemEntry
							+ " - " + e.getMessage());
				}
			}
		}

		StringBuilder equipCache = new StringBuilder();
		for (int i = 0; i < 19; i++) {
			if (i > 0)
				equipCache.append(' ');
			equipCache.append(equipCacheEntries[i]).append(' ').append(0);
		}
		try (PreparedStatement upd = conn
				.prepareStatement("UPDATE characters SET equipmentCache = ?, bankSlots = ? WHERE guid = ?")) {
			upd.setString(1, equipCache.toString());
			upd.setInt(2, bankSlotsNeeded);
			upd.setInt(3, guid);
			upd.executeUpdate();
		}

		for (InventoryItem item : allItems) {
			if (item.getBag() != 0)
				continue;
			long srcGuid = item.getItemGuid();
			Long newGuid = sourceToNewGuid.get(srcGuid);
			if (newGuid == null)
				continue;
			int itemEntry;
			try {
				itemEntry = Integer.parseInt(item.getRefId());
			} catch (NumberFormatException ignored) {
				continue;
			}
			if (!ensureItemTemplateExists(conn, itemEntry, item.getTemplateBlob())) {
				log.warning("importInventory: skipping top-level item entry=" + itemEntry + " slot=" + item.getSlot()
						+ " - not in item_template and no blob available");
				continue;
			}
			try {
				insertItemInstance(conn, newGuid, itemEntry, guid, item.getCount(), item.getDurability(), item.getItemText());
				insertInventoryEntry(conn, guid, 0L, item.getSlot(), newGuid);
			} catch (SQLException e) {
				log.warning("importInventory: skipping top-level item entry=" + itemEntry + " slot=" + item.getSlot()
						+ " - " + e.getMessage());
			}
		}

		for (InventoryItem item : allItems) {
			if (item.getBag() == 0)
				continue;
			Long newBagGuid = sourceToNewGuid.get(item.getBag());
			if (newBagGuid == null) {
				log.warning(
						"importInventory: no newGuid for bag=" + item.getBag() + " - skipping slot " + item.getSlot());
				continue;
			}
			long srcGuid = item.getItemGuid();
			Long newGuid = sourceToNewGuid.get(srcGuid);
			if (newGuid == null)
				continue;
			int itemEntry;
			try {
				itemEntry = Integer.parseInt(item.getRefId());
			} catch (NumberFormatException ignored) {
				continue;
			}
			if (!ensureItemTemplateExists(conn, itemEntry, item.getTemplateBlob())) {
				log.warning("importInventory: skipping bag content entry=" + itemEntry + " bag=" + item.getBag()
						+ " slot=" + item.getSlot() + " - not in item_template and no blob available");
				continue;
			}
			try {
				insertItemInstance(conn, newGuid, itemEntry, guid, item.getCount(), item.getDurability(), item.getItemText());
				insertInventoryEntry(conn, guid, newBagGuid, item.getSlot(), newGuid);
			} catch (SQLException e) {
				log.warning("importInventory: skipping bag content entry=" + itemEntry + " bag=" + item.getBag()
						+ " slot=" + item.getSlot() + " - " + e.getMessage());
			}
		}

		log.info("importInventory complete - guid=" + guid + " equipment=" + (equipment != null ? equipment.size() : 0)
				+ " inventory=" + (inventory != null ? inventory.size() : 0) + " bank="
				+ (bank != null ? bank.size() : 0) + " bankSlots=" + bankSlotsNeeded);
	}

	private void insertItemInstance(Connection conn, long itemGuid, int itemEntry, int ownerGuid, int count,
			int durability, String itemText) throws SQLException {
		final String EMPTY_ENCHANTMENTS = "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 ";

		String sql = "INSERT INTO item_instance" + " (guid, itemEntry, owner_guid, creatorGuid, giftCreatorGuid,"
				+ "  count, duration, charges, flags, enchantments,"
				+ "  randomPropertyId, durability, playedTime, `text`)"
				+ " VALUES (?, ?, ?, 0, 0, ?, 0, '', 0, ?, 0, ?, 0, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, itemGuid);
			ps.setInt(2, itemEntry);
			ps.setInt(3, ownerGuid);
			ps.setInt(4, count);
			ps.setString(5, EMPTY_ENCHANTMENTS);
			ps.setInt(6, durability);
			ps.setString(7, itemText != null ? itemText : "");
			ps.executeUpdate();
		}
	}

	private void insertInventoryEntry(Connection conn, int charGuid, long bagGuid, int slot, long itemGuid)
			throws SQLException {
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
		if (slotLabel == null || "active".equals(slotLabel))
			return 0;
		if (slotLabel.startsWith("stable_")) {
			try {
				return Integer.parseInt(slotLabel.substring(7));
			} catch (NumberFormatException ignored) {
			}
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

	private Map<Integer, String> buildTemplateBlobMap(Set<Integer> entries) {
		Set<Integer> candidates = entries.stream().filter(e -> e >= customItemThreshold).collect(Collectors.toSet());

		if (candidates.isEmpty())
			return Collections.emptyMap();

		if (worldDataSource == null) {
			log.fine("buildTemplateBlobMap: world database not configured - skipping custom template blobs");
			return Collections.emptyMap();
		}

		String inClause = candidates.stream().map(String::valueOf).collect(Collectors.joining(","));

		String sql = "SELECT entry, `class`, subclass, name, displayid, Quality, bonding, description,"
				+ "  InventoryType, AllowableClass, AllowableRace, ItemLevel, RequiredLevel,"
				+ "  RequiredSkill, RequiredSkillRank, Material, sheath, StatsCount,"
				+ "  stat_type1,  stat_value1,  stat_type2,  stat_value2,"
				+ "  stat_type3,  stat_value3,  stat_type4,  stat_value4,"
				+ "  stat_type5,  stat_value5,  stat_type6,  stat_value6,"
				+ "  stat_type7,  stat_value7,  stat_type8,  stat_value8,"
				+ "  stat_type9,  stat_value9,  stat_type10, stat_value10,"
				+ "  dmg_min1, dmg_max1, dmg_type1, delay, armor,"
				+ "  ContainerSlots, MaxCount, Stackable, BuyPrice, SellPrice,"
				+ "  spellid_1, spelltrigger_1, spellcharges_1," + "  spellid_2, spelltrigger_2, spellcharges_2,"
				+ "  spellid_3, spelltrigger_3, spellcharges_3," + "  spellid_4, spelltrigger_4, spellcharges_4,"
				+ "  spellid_5, spelltrigger_5, spellcharges_5," + "  Flags, FlagsExtra"
				+ " FROM item_template WHERE entry IN (" + inClause + ")";

		Map<Integer, String> result = new HashMap<>();
		try (Connection worldConn = worldDataSource.getConnection();
				Statement st = worldConn.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				Map<String, Object> blob = new LinkedHashMap<>();
				blob.put("class", rs.getInt("class"));
				blob.put("subclass", rs.getInt("subclass"));
				blob.put("name", rs.getString("name"));
				blob.put("displayid", rs.getInt("displayid"));
				blob.put("Quality", rs.getInt("Quality"));
				blob.put("bonding", rs.getInt("bonding"));
				blob.put("description", rs.getString("description"));
				blob.put("InventoryType", rs.getInt("InventoryType"));
				blob.put("AllowableClass", rs.getInt("AllowableClass"));
				blob.put("AllowableRace", rs.getInt("AllowableRace"));
				blob.put("ItemLevel", rs.getInt("ItemLevel"));
				blob.put("RequiredLevel", rs.getInt("RequiredLevel"));
				blob.put("RequiredSkill", rs.getInt("RequiredSkill"));
				blob.put("RequiredSkillRank", rs.getInt("RequiredSkillRank"));
				blob.put("Material", rs.getInt("Material"));
				blob.put("sheath", rs.getInt("sheath"));
				blob.put("StatsCount", rs.getInt("StatsCount"));
				for (int i = 1; i <= 10; i++) {
					blob.put("stat_type" + i, rs.getInt("stat_type" + i));
					blob.put("stat_value" + i, rs.getInt("stat_value" + i));
				}
				blob.put("dmg_min1", rs.getDouble("dmg_min1"));
				blob.put("dmg_max1", rs.getDouble("dmg_max1"));
				blob.put("dmg_type1", rs.getInt("dmg_type1"));
				blob.put("delay", rs.getInt("delay"));
				blob.put("armor", rs.getInt("armor"));
				blob.put("ContainerSlots", rs.getInt("ContainerSlots"));
				blob.put("MaxCount", rs.getInt("MaxCount"));
				blob.put("Stackable", rs.getInt("Stackable"));
				blob.put("BuyPrice", rs.getLong("BuyPrice"));
				blob.put("SellPrice", rs.getLong("SellPrice"));
				for (int i = 1; i <= 5; i++) {
					blob.put("spellid_" + i, rs.getInt("spellid_" + i));
					blob.put("spelltrigger_" + i, rs.getInt("spelltrigger_" + i));
					blob.put("spellcharges_" + i, rs.getInt("spellcharges_" + i));
				}
				blob.put("Flags", rs.getInt("Flags"));
				blob.put("FlagsExtra", rs.getInt("FlagsExtra"));

				try {
					String json = blobMapper.writeValueAsString(blob);
					String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
					result.put(rs.getInt("entry"), b64);
				} catch (Exception e) {
					log.warning("buildTemplateBlobMap: failed to encode entry=" + rs.getInt("entry") + ": "
							+ e.getMessage());
				}
			}
		} catch (SQLException e) {
			log.warning("buildTemplateBlobMap: query failed - " + e.getMessage());
		}
		return result;
	}

	private boolean ensureItemTemplateExists(Connection conn, int itemEntry, String templateBlob) {
		if (itemEntry < customItemThreshold)
			return true;

		if (worldDataSource == null) {
			log.warning("ensureItemTemplateExists: world database not configured"
					+ " - cannot validate or install custom item entry=" + itemEntry
					+ ". Call setWorldDatabase() to enable custom item support.");
			return false;
		}

		try (Connection worldConn = worldDataSource.getConnection();
				PreparedStatement ps = worldConn
						.prepareStatement("SELECT 1 FROM item_template WHERE entry = ? LIMIT 1")) {
			ps.setInt(1, itemEntry);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next())
					return true;
			}
		} catch (SQLException e) {
			log.warning("ensureItemTemplateExists: lookup failed for entry=" + itemEntry + ": " + e.getMessage());
			return false;
		}

		if (templateBlob == null)
			return false;

		try {
			byte[] bytes = Base64.getDecoder().decode(templateBlob);
			Map<String, Object> data = blobMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {
			});
			try (Connection worldConn = worldDataSource.getConnection()) {
				insertItemTemplate(worldConn, itemEntry, data);
			}
			log.info("Installed custom item_template entry=" + itemEntry);
			return true;
		} catch (Exception e) {
			log.warning("ensureItemTemplateExists: failed to install template for entry=" + itemEntry + ": "
					+ e.getMessage());
			return false;
		}
	}

	private void insertItemTemplate(Connection conn, int entry, Map<String, Object> d) throws SQLException {
		String sql = "INSERT IGNORE INTO item_template ("
				+ "  entry, `class`, subclass, name, displayid, Quality, bonding, description,"
				+ "  InventoryType, AllowableClass, AllowableRace, ItemLevel, RequiredLevel,"
				+ "  RequiredSkill, RequiredSkillRank, Material, sheath, StatsCount,"
				+ "  stat_type1,  stat_value1,  stat_type2,  stat_value2,"
				+ "  stat_type3,  stat_value3,  stat_type4,  stat_value4,"
				+ "  stat_type5,  stat_value5,  stat_type6,  stat_value6,"
				+ "  stat_type7,  stat_value7,  stat_type8,  stat_value8,"
				+ "  stat_type9,  stat_value9,  stat_type10, stat_value10,"
				+ "  dmg_min1, dmg_max1, dmg_type1, delay, armor,"
				+ "  ContainerSlots, MaxCount, Stackable, BuyPrice, SellPrice,"
				+ "  spellid_1, spelltrigger_1, spellcharges_1," + "  spellid_2, spelltrigger_2, spellcharges_2,"
				+ "  spellid_3, spelltrigger_3, spellcharges_3," + "  spellid_4, spelltrigger_4, spellcharges_4,"
				+ "  spellid_5, spelltrigger_5, spellcharges_5," + "  Flags, FlagsExtra"
				+ ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int p = 1;
			ps.setInt(p++, entry);
			ps.setInt(p++, intVal(d, "class"));
			ps.setInt(p++, intVal(d, "subclass"));
			ps.setString(p++, strVal(d, "name"));
			ps.setInt(p++, intVal(d, "displayid"));
			ps.setInt(p++, intVal(d, "Quality"));
			ps.setInt(p++, intVal(d, "bonding"));
			ps.setString(p++, strVal(d, "description"));
			ps.setInt(p++, intVal(d, "InventoryType"));
			ps.setInt(p++, intVal(d, "AllowableClass"));
			ps.setInt(p++, intVal(d, "AllowableRace"));
			ps.setInt(p++, intVal(d, "ItemLevel"));
			ps.setInt(p++, intVal(d, "RequiredLevel"));
			ps.setInt(p++, intVal(d, "RequiredSkill"));
			ps.setInt(p++, intVal(d, "RequiredSkillRank"));
			ps.setInt(p++, intVal(d, "Material"));
			ps.setInt(p++, intVal(d, "sheath"));
			ps.setInt(p++, intVal(d, "StatsCount"));
			for (int i = 1; i <= 10; i++) {
				ps.setInt(p++, intVal(d, "stat_type" + i));
				ps.setInt(p++, intVal(d, "stat_value" + i));
			}
			ps.setDouble(p++, dblVal(d, "dmg_min1"));
			ps.setDouble(p++, dblVal(d, "dmg_max1"));
			ps.setInt(p++, intVal(d, "dmg_type1"));
			ps.setInt(p++, intVal(d, "delay"));
			ps.setInt(p++, intVal(d, "armor"));
			ps.setInt(p++, intVal(d, "ContainerSlots"));
			ps.setInt(p++, intVal(d, "MaxCount"));
			ps.setInt(p++, intVal(d, "Stackable"));
			ps.setLong(p++, longVal(d, "BuyPrice"));
			ps.setLong(p++, longVal(d, "SellPrice"));
			for (int i = 1; i <= 5; i++) {
				ps.setInt(p++, intVal(d, "spellid_" + i));
				ps.setInt(p++, intVal(d, "spelltrigger_" + i));
				ps.setInt(p++, intVal(d, "spellcharges_" + i));
			}
			ps.setInt(p++, intVal(d, "Flags"));
			ps.setInt(p, intVal(d, "FlagsExtra"));
			ps.executeUpdate();
		}
	}

	private int intVal(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v instanceof Number ? ((Number) v).intValue() : 0;
	}

	private long longVal(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v instanceof Number ? ((Number) v).longValue() : 0L;
	}

	private double dblVal(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
	}

	private String strVal(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v != null ? String.valueOf(v) : "";
	}

	private String capitalize(String s) {
		if (s == null || s.isEmpty())
			return s;
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
	}

	public void close() {
		dataSource.close();
		if (worldDataSource != null)
			worldDataSource.close();
	}
}
