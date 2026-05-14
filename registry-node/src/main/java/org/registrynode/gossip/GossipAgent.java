package org.registrynode.gossip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.registrynode.model.SnapshotRecord;
import org.registrynode.storage.SnapshotStore;
import org.registrynode.storage.SnapshotStore.StoreResult;
import org.registrynode.verify.RecordVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class GossipAgent {

	private static final Logger log = Logger.getLogger(GossipAgent.class.getName());

	private static final long SYNC_DELAY_SECONDS = 5;
	private static final long HEALTH_CHECK_MINUTES = 2;

	private final SnapshotStore store;
	private final RecordVerifier verifier;
	private final List<PeerNode> peers;
	private final ObjectMapper mapper;

	private final ExecutorService pushPool = Executors.newVirtualThreadPerTaskExecutor();

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "gossip-scheduler");
		t.setDaemon(true);
		return t;
	});

	public GossipAgent(SnapshotStore store, RecordVerifier verifier, List<String> peerUrls) {
		this.store = store;
		this.verifier = verifier;
		this.mapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
				.configure(SerializationFeature.INDENT_OUTPUT, false);

		this.peers = new ArrayList<>();
		for (String url : peerUrls) {
			peers.add(new PeerNode(url));
		}

		log.info("GossipAgent initialised with " + peers.size() + " peers");
	}

	public void start() {
		scheduler.schedule(this::startupSync, SYNC_DELAY_SECONDS, TimeUnit.SECONDS);

		scheduler.scheduleAtFixedRate(this::healthCheck, HEALTH_CHECK_MINUTES, HEALTH_CHECK_MINUTES, TimeUnit.MINUTES);

		log.info("GossipAgent started");
	}

	public void gossip(SnapshotRecord record) {
		List<PeerNode> available = availablePeers();
		if (available.isEmpty()) {
			log.fine("No available peers to gossip to");
			return;
		}

		String json;
		try {
			json = mapper.writeValueAsString(record);
		} catch (Exception e) {
			log.warning("Failed to serialise record for gossip: " + e.getMessage());
			return;
		}

		int targetCount = available.size();
		log.fine("Gossiping characterId=" + record.getCharacterId() + " sequence=" + record.getSequence() + " to "
				+ targetCount + " peers");

		for (PeerNode peer : available) {
			final String payload = json;
			pushPool.submit(() -> {
				boolean ok = peer.push(payload);
				if (!ok) {
					log.fine("Gossip push failed to " + peer.getBaseUrl());
				}
			});
		}
	}

	private void startupSync() {
		log.info("Starting startup sync with " + peers.size() + " peers...");

		int totalFetched = 0;
		int totalStored = 0;

		for (PeerNode peer : peers) {
			if (!peer.isAvailable()) {
				log.fine("Skipping unavailable peer: " + peer.getBaseUrl());
				continue;
			}

			log.info("Syncing from peer: " + peer.getBaseUrl());

			String listJson = peer.fetchCharacterList();
			if (listJson == null) {
				log.warning("Could not fetch character list from " + peer.getBaseUrl());
				continue;
			}

			List<String> theirIds = parseStringArray(listJson);
			if (theirIds == null || theirIds.isEmpty()) {
				log.info("Peer " + peer.getBaseUrl() + " has no records");
				continue;
			}

			log.info("Peer " + peer.getBaseUrl() + " knows " + theirIds.size() + " characters");

			for (String characterId : theirIds) {
				long ourSeq = store.currentSequence(characterId);
				if (store.exists(characterId)) {
					continue;
				}

				totalFetched++;

				String recordJson = peer.fetchRecord(characterId);
				if (recordJson == null)
					continue;

				SnapshotRecord record = parseRecord(recordJson);
				if (record == null)
					continue;

				RecordVerifier.VerificationResult result = verifier.verify(record);
				if (!result.isValid()) {
					log.warning("Sync: invalid record from peer " + peer.getBaseUrl() + " — " + result.getReason());
					continue;
				}

				StoreResult storeResult = store.put(record);
				if (storeResult == StoreResult.STORED) {
					totalStored++;
					log.fine("Sync stored: characterId=" + characterId);
				}
			}
		}

		log.info("Startup sync complete — fetched=" + totalFetched + " stored=" + totalStored);
	}

	private void healthCheck() {
		int healthy = 0;
		int total = peers.size();

		for (PeerNode peer : peers) {
			if (peer.ping()) {
				healthy++;
			}
		}

		log.info("Peer health: " + healthy + "/" + total + " reachable");
	}

	public void addPeer(String url) {
		boolean alreadyKnown = peers.stream().anyMatch(p -> p.getBaseUrl().equalsIgnoreCase(url));

		if (!alreadyKnown) {
			peers.add(new PeerNode(url));
			log.info("New peer discovered and added: " + url);
		}
	}

	public int peerCount() {
		return peers.size();
	}

	public List<String> peerStatus() {
		List<String> status = new ArrayList<>();
		for (PeerNode peer : peers) {
			status.add(peer.getBaseUrl() + " [" + (peer.isHealthy() ? "healthy" : "unhealthy") + "]");
		}
		return status;
	}

	private List<PeerNode> availablePeers() {
		List<PeerNode> available = new ArrayList<>();
		for (PeerNode peer : peers) {
			if (peer.isAvailable())
				available.add(peer);
		}
		Collections.shuffle(available);
		return available;
	}

	private SnapshotRecord parseRecord(String json) {
		try {
			return mapper.readValue(json, SnapshotRecord.class);
		} catch (Exception e) {
			log.warning("Failed to parse record JSON: " + e.getMessage());
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> parseStringArray(String json) {
		try {
			return mapper.readValue(json, List.class);
		} catch (Exception e) {
			log.warning("Failed to parse character list JSON: " + e.getMessage());
			return null;
		}
	}

	public void shutdown() {
		scheduler.shutdown();
		pushPool.shutdown();
		log.info("GossipAgent shutdown");
	}
}
