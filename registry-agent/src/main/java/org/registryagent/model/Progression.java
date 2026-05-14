package org.registryagent.model;

import java.util.List;
import java.util.Map;


public class Progression {

	private Map<String, Map<String, Integer>> skills;

	private Map<String, Map<String, Integer>> reputation;

	private Map<String, List<Integer>> questsCompleted;

	private Map<String, List<Integer>> talents;

	private Map<String, List<Integer>> flags;

	public Progression() {
	}

	public Map<String, Map<String, Integer>> getSkills() {
		return skills;
	}

	public void setSkills(Map<String, Map<String, Integer>> skills) {
		this.skills = skills;
	}

	public Map<String, Map<String, Integer>> getReputation() {
		return reputation;
	}

	public void setReputation(Map<String, Map<String, Integer>> reputation) {
		this.reputation = reputation;
	}

	public Map<String, List<Integer>> getQuestsCompleted() {
		return questsCompleted;
	}

	public void setQuestsCompleted(Map<String, List<Integer>> questsCompleted) {
		this.questsCompleted = questsCompleted;
	}

	public Map<String, List<Integer>> getTalents() {
		return talents;
	}

	public void setTalents(Map<String, List<Integer>> talents) {
		this.talents = talents;
	}

	public Map<String, List<Integer>> getFlags() {
		return flags;
	}

	public void setFlags(Map<String, List<Integer>> flags) {
		this.flags = flags;
	}

	public Map<String, Integer> getSkillsFor(String namespace) {
		if (skills == null)
			return null;
		return skills.get(namespace);
	}

	public Map<String, Integer> getReputationFor(String namespace) {
		if (reputation == null)
			return null;
		return reputation.get(namespace);
	}

	public List<Integer> getQuestsFor(String namespace) {
		if (questsCompleted == null)
			return null;
		return questsCompleted.get(namespace);
	}

	public List<Integer> getTalentsFor(String namespace) {
		if (talents == null)
			return null;
		return talents.get(namespace);
	}

	public List<Integer> getFlagsFor(String namespace) {
		if (flags == null)
			return null;
		return flags.get(namespace);
	}
}
