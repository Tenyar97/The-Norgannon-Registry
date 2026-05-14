package org.registryagent.model;

public class CharacterIdentity {

	private String name;
	private String gender;
	private int level;

	private String classRef;
	private String classNamespace;
	private String classLabel;

	private String raceRef;
	private String raceNamespace;
	private String raceLabel;

	public CharacterIdentity() {
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getGender() {
		return gender;
	}

	public void setGender(String gender) {
		this.gender = gender;
	}

	public int getLevel() {
		return level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public String getClassRef() {
		return classRef;
	}

	public void setClassRef(String classRef) {
		this.classRef = classRef;
	}

	public String getClassNamespace() {
		return classNamespace;
	}

	public void setClassNamespace(String classNamespace) {
		this.classNamespace = classNamespace;
	}

	public String getClassLabel() {
		return classLabel;
	}

	public void setClassLabel(String classLabel) {
		this.classLabel = classLabel;
	}

	public String getRaceRef() {
		return raceRef;
	}

	public void setRaceRef(String raceRef) {
		this.raceRef = raceRef;
	}

	public String getRaceNamespace() {
		return raceNamespace;
	}

	public void setRaceNamespace(String raceNamespace) {
		this.raceNamespace = raceNamespace;
	}

	public String getRaceLabel() {
		return raceLabel;
	}

	public void setRaceLabel(String raceLabel) {
		this.raceLabel = raceLabel;
	}

	public String qualifiedClass() {
		return classNamespace + ":" + classRef;
	}

	public String qualifiedRace() {
		return raceNamespace + ":" + raceRef;
	}

	@Override
	public String toString() {
		return String.format("CharacterIdentity{name='%s', class='%s', race='%s', level=%d}", name, qualifiedClass(),
				qualifiedRace(), level);
	}
}
