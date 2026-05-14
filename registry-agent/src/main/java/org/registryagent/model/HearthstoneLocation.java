package org.registryagent.model;

public class HearthstoneLocation {

	private String namespace;
	private int mapId;
	private int zoneId;
	private String zoneName; // may be null if zoneId is not in the lookup table
	private float x;
	private float y;
	private float z;

	public HearthstoneLocation() {
	}

	public HearthstoneLocation(String namespace, int mapId, int zoneId, String zoneName, float x, float y, float z) {
		this.namespace = namespace;
		this.mapId = mapId;
		this.zoneId = zoneId;
		this.zoneName = zoneName;
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public int getMapId() {
		return mapId;
	}

	public void setMapId(int mapId) {
		this.mapId = mapId;
	}

	public int getZoneId() {
		return zoneId;
	}

	public void setZoneId(int zoneId) {
		this.zoneId = zoneId;
	}

	public String getZoneName() {
		return zoneName;
	}

	public void setZoneName(String zoneName) {
		this.zoneName = zoneName;
	}

	public float getX() {
		return x;
	}

	public void setX(float x) {
		this.x = x;
	}

	public float getY() {
		return y;
	}

	public void setY(float y) {
		this.y = y;
	}

	public float getZ() {
		return z;
	}

	public void setZ(float z) {
		this.z = z;
	}

	@Override
	public String toString() {
		return String.format("HearthstoneLocation{zone='%s'(%d), map=%d, pos=(%.1f,%.1f,%.1f)}",
				zoneName != null ? zoneName : "unknown", zoneId, mapId, x, y, z);
	}
}
