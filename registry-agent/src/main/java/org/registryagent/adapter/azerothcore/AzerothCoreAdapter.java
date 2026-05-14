package org.registryagent.adapter.azerothcore;

import org.registryagent.adapter.trinitycore.TrinityCoreAdapter;
import org.registryagent.adapter.trinitycore.TrinityCoreMaps;
import org.registryagent.model.CharacterStats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public class AzerothCoreAdapter extends TrinityCoreAdapter {

	private static final Logger log = Logger.getLogger(AzerothCoreAdapter.class.getName());

	public static final String NAMESPACE = "wow_wotlk_azerothcore";

	public AzerothCoreAdapter(String jdbcUrl, String username, String password) {
		super(jdbcUrl, username, password);
		log.info("AzerothCoreAdapter initialised — namespace=" + NAMESPACE);
	}

	@Override
	public String getNamespace() {
		return NAMESPACE;
	}

	@Override
	protected CharacterStats readStats(Connection conn, int guid) throws SQLException {

		// Writing our own table for char stats because of course it's better
		String registrySql = "SELECT strength, agility, stamina, intellect, spirit,"
				+ " maxhealth, maxmana, armor, attack_power"
				+ " FROM registry_character_stats WHERE guid = ?";

		try (PreparedStatement ps = conn.prepareStatement(registrySql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					CharacterStats stats = new CharacterStats();
					stats.setStrength(rs.getInt("strength"));
					stats.setAgility(rs.getInt("agility"));
					stats.setStamina(rs.getInt("stamina"));
					stats.setIntellect(rs.getInt("intellect"));
					stats.setSpirit(rs.getInt("spirit"));
					stats.setHealth(rs.getInt("maxhealth"));
					stats.setMana(rs.getInt("maxmana"));
					stats.setDefense(rs.getInt("armor"));
					stats.setAttackPower(rs.getInt("attack_power"));

					Map<String, Integer> ext = new LinkedHashMap<>();
					int armor       = rs.getInt("armor");
					int attackPower = rs.getInt("attack_power");
					if (armor       > 0) ext.put("armor",        armor);
					if (attackPower > 0) ext.put("attack_power", attackPower);
					if (!ext.isEmpty()) stats.setStatExtensions(Map.of(NAMESPACE, ext));

					log.fine("registry_character_stats OK for guid=" + guid
							+ " str=" + stats.getStrength());
					return stats;
				}
			}
		} catch (SQLException e) {
			log.fine("registry_character_stats not available for guid=" + guid + ": " + e.getMessage());
		}

		String acSql = "SELECT cs.maxhealth, cs.maxpower1, cs.maxpower2, cs.maxpower3, cs.maxpower4, cs.maxpower5, "
				+ "  cs.strength, cs.agility, cs.stamina, cs.intellect, cs.spirit, "
				+ "  cs.armor, cs.attackPower, cs.spellPower, c.class AS classId "
				+ "FROM character_stats cs "
				+ "JOIN characters c ON c.guid = cs.guid "
				+ "WHERE cs.guid = ?";

		try (PreparedStatement ps = conn.prepareStatement(acSql)) {
			ps.setInt(1, guid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return buildStatsFromCharacterStats(rs);
				}
			}
		} catch (SQLException e) {
			log.fine("character_stats not available for guid=" + guid + ": " + e.getMessage());
		}

		return super.readStats(conn, guid);
	}

	private CharacterStats buildStatsFromCharacterStats(ResultSet rs) throws SQLException {
		CharacterStats stats = new CharacterStats();

		stats.setStrength(rs.getInt("strength"));
		stats.setAgility(rs.getInt("agility"));
		stats.setStamina(rs.getInt("stamina"));
		stats.setIntellect(rs.getInt("intellect"));
		stats.setSpirit(rs.getInt("spirit"));

		stats.setHealth(rs.getInt("maxhealth"));
		stats.setMana(rs.getInt("maxpower1"));
		stats.setAttackPower(rs.getInt("attackPower"));
		stats.setSpellPower(rs.getInt("spellPower"));
		stats.setDefense(rs.getInt("armor"));

		int classId = rs.getInt("classId");
		int primaryPowerIdx = TrinityCoreMaps.CLASS_PRIMARY_POWER.getOrDefault(classId, 1);

		Map<String, Integer> acExtensions = new LinkedHashMap<>();

		int primaryMaxPower = rs.getInt("maxpower" + primaryPowerIdx);
		if (primaryMaxPower > 0) {
			String powerName = TrinityCoreMaps.POWER_ID_TO_NAME.getOrDefault(primaryPowerIdx, "power" + primaryPowerIdx);
			acExtensions.put("max_" + powerName, primaryMaxPower);
		}
		if (classId == 6) {
			int maxRune = rs.getInt("maxpower6");
			if (maxRune > 0) acExtensions.put("max_rune", maxRune);
		}

		acExtensions.put("armor", rs.getInt("armor"));
		acExtensions.put("attack_power", rs.getInt("attackPower"));
		acExtensions.put("spell_power", rs.getInt("spellPower"));

		if (!acExtensions.isEmpty()) {
			stats.setStatExtensions(Map.of(NAMESPACE, acExtensions));
		}

		return stats;
	}

}
