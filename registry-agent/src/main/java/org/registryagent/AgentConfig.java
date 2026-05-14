package org.registryagent;

import java.util.List;

public class AgentConfig {

	private String serverId;

	private String privateKeyPath = "./keys/server.pem";
	private String publicKeyPath = "./keys/server.pub";

	private String dbUrl;
	private String dbUsername;
	private String dbPassword;

	private String dbAdapter = "trinitycore_3.3.5a";

	private List<String> registryNodes;

	public String getServerId() {
		return serverId;
	}

	public void setServerId(String v) {
		this.serverId = v;
	}

	public String getPrivateKeyPath() {
		return privateKeyPath;
	}

	public void setPrivateKeyPath(String v) {
		this.privateKeyPath = v;
	}

	public String getPublicKeyPath() {
		return publicKeyPath;
	}

	public void setPublicKeyPath(String v) {
		this.publicKeyPath = v;
	}

	public String getDbUrl() {
		return dbUrl;
	}

	public void setDbUrl(String v) {
		this.dbUrl = v;
	}

	public String getDbUsername() {
		return dbUsername;
	}

	public void setDbUsername(String v) {
		this.dbUsername = v;
	}

	public String getDbPassword() {
		return dbPassword;
	}

	public void setDbPassword(String v) {
		this.dbPassword = v;
	}

	public String getDbAdapter() {
		return dbAdapter;
	}

	public void setDbAdapter(String v) {
		this.dbAdapter = v;
	}

	public List<String> getRegistryNodes() {
		return registryNodes;
	}

	public void setRegistryNodes(List<String> v) {
		this.registryNodes = v;
	}

	public void validate() {
		require(serverId, "server_id");
		require(privateKeyPath, "keys.private_key_path");
		require(publicKeyPath, "keys.public_key_path");
		require(dbUrl, "database.url");
		require(dbUsername, "database.username");
		require(dbPassword, "database.password");

		if (registryNodes == null || registryNodes.isEmpty()) {
			throw new IllegalStateException("Config error: registry.nodes must contain at least one node URL");
		}
	}

	private void require(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Config error: missing required field '" + fieldName + "'");
		}
	}
}
