package org.registryagent.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class TrustStore {

	private static final Logger log = Logger.getLogger(TrustStore.class.getName());

	private final Path configFile;
	private final ObjectMapper mapper;

	private volatile boolean trustAll;

	private final Map<String, TrustedServer> trusted = new ConcurrentHashMap<>();

	public static TrustStore load(Path configFile) {
		TrustStore store = new TrustStore(configFile);
		store.loadFromDisk();
		return store;
	}

	public static TrustStore load() {
		return load(Path.of("trust.json"));
	}

	private TrustStore(Path configFile) {
		this.configFile = configFile;
		this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	}

	public boolean isTrusted(String serverPubKey) {
		if (trustAll)
			return true;
		if (serverPubKey == null || serverPubKey.isBlank())
			return false;
		return trusted.containsKey(serverPubKey.toLowerCase());
	}

	public Optional<TrustedServer> getEntry(String serverPubKey) {
		if (serverPubKey == null)
			return Optional.empty();
		return Optional.ofNullable(trusted.get(serverPubKey.toLowerCase()));
	}

	public synchronized void add(TrustedServer entry) {
		if (entry.getPubKey() == null || entry.getPubKey().isBlank()) {
			throw new IllegalArgumentException("TrustedServer pub_key must not be blank");
		}
		String key = entry.getPubKey().toLowerCase();
		if (entry.getAddedAt() == null) {
			entry.setAddedAt(Instant.now().toString());
		}
		trusted.put(key, entry);

		trustAll = false;

		save();
		log.info("TrustStore: added server — " + entry);
	}

	public synchronized boolean remove(String serverPubKey) {
		if (serverPubKey == null)
			return false;
		TrustedServer removed = trusted.remove(serverPubKey.toLowerCase());
		if (removed != null) {
			save();
			log.info("TrustStore: removed server — " + removed);
			return true;
		}
		return false;
	}

	public List<TrustedServer> list() {
		return Collections.unmodifiableList(new ArrayList<>(trusted.values()));
	}

	public boolean isTrustAll() {
		return trustAll;
	}

	public synchronized void setTrustAll(boolean trustAll) {
		this.trustAll = trustAll;
		save();
		log.info("TrustStore: trust_all set to " + trustAll);
	}

	private void loadFromDisk() {
		File file = configFile.toFile();
		if (!file.exists()) {
			log.info("TrustStore: " + configFile + " not found — starting in trust-all mode. "
					+ "Use POST /admin/trust to add trusted servers (switches to strict mode).");
			trustAll = true;
			return;
		}
		try {
			TrustStoreFile data = mapper.readValue(file, TrustStoreFile.class);
			trustAll = data.trustAll;
			trusted.clear();
			if (data.trustedServers != null) {
				for (TrustedServer s : data.trustedServers) {
					if (s.getPubKey() != null && !s.getPubKey().isBlank()) {
						trusted.put(s.getPubKey().toLowerCase(), s);
					}
				}
			}
			log.info("TrustStore: loaded " + trusted.size() + " trusted server(s) " + "(trust_all=" + trustAll
					+ ") from " + configFile);
		} catch (IOException e) {
			log.severe("TrustStore: failed to parse " + configFile + " — defaulting to trust-all. " + "Error: "
					+ e.getMessage());
			trustAll = true;
		}
	}

	private void save() {
		TrustStoreFile data = new TrustStoreFile();
		data.trustAll = this.trustAll;
		data.trustedServers = new ArrayList<>(trusted.values());
		try {
			File out = configFile.toFile();
			out.getParentFile().mkdirs();
			mapper.writeValue(out, data);
			log.fine("TrustStore: saved to " + configFile);
		} catch (IOException e) {
			log.severe("TrustStore: failed to save " + configFile + ": " + e.getMessage());
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class TrustStoreFile {
		@JsonProperty("trust_all")
		boolean trustAll = true;

		@JsonProperty("trusted_servers")
		List<TrustedServer> trustedServers = new ArrayList<>();
	}
}
