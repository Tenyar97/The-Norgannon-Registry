package org.registryagent.registry;

import org.registryagent.model.SnapshotRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class RegistryClient {

	private static final Logger log = Logger.getLogger(RegistryClient.class.getName());

	private static final int QUORUM = 1;

	private final List<RegistryNode> nodes;
	private final NodeTransport transport;

	private final ExecutorService broadcastPool = Executors.newVirtualThreadPerTaskExecutor();

	private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "registry-retry");
		t.setDaemon(true);
		return t;
	});

	private final List<SnapshotRecord> retryQueue = Collections.synchronizedList(new ArrayList<>());

	public RegistryClient(List<String> nodeUrls, NodeTransport transport) {
		this.transport = transport;
		this.nodes = nodeUrls.stream().map(RegistryNode::new)
				.collect(Collectors.toCollection(ArrayList::new));

		log.info("RegistryClient initialized with " + nodes.size() + " nodes");

		retryScheduler.scheduleAtFixedRate(() -> {
			if (!retryQueue.isEmpty()) {
				List<RegistryNode> available = availableNodes();
				if (!available.isEmpty()) {
					log.info("Scheduled retry: flushing " + retryQueue.size() + " queued snapshot(s)");
					flushRetryQueue(available);
				}
			}
		}, 30, 30, TimeUnit.SECONDS);
	}

	public boolean characterExists(String characterId, String playerPubKey) {
		SnapshotRecord record = fetchLatest(characterId, playerPubKey);
		return record != null;
	}

	public SnapshotRecord fetchLatest(String characterId, String playerPubKey) {
		List<RegistryNode> shuffled = availableNodes();

		if (shuffled.isEmpty()) {
			log.warning("No available nodes to fetch from — characterId=" + characterId);
			return null;
		}

		SnapshotRecord best = null;

		for (RegistryNode node : shuffled) {
			SnapshotRecord record = transport.fetch(node, characterId);
			if (record == null)
				continue;

			if (!playerPubKey.equals(record.getPlayerPubKey())) {
				log.warning("Ownership mismatch — characterId=" + characterId + " expected key="
						+ playerPubKey.substring(0, 8) + "..." + " got=" + record.getPlayerPubKey().substring(0, 8)
						+ "...");
				continue;
			}

			if (best == null || record.getSequence() > best.getSequence()) {
				best = record;
			}

			break;
		}

		if (best != null) {
			log.info("Fetched record — characterId=" + characterId + " sequence=" + best.getSequence());
		}

		return best;
	}

	public void broadcast(SnapshotRecord record) {
		List<RegistryNode> targets = availableNodes();

		if (targets.isEmpty()) {
			log.warning("No available nodes — queuing snapshot for retry. " + "characterId=" + record.getCharacterId());
			retryQueue.add(record);
			return;
		}

		List<Future<Boolean>> futures = new ArrayList<>();

		for (RegistryNode node : targets) {
			futures.add(broadcastPool.submit(() -> transport.push(node, record)));
		}

		int acks = 0;
		for (Future<Boolean> future : futures) {
			try {
				if (future.get())
					acks++;
			} catch (Exception e) {
			}
		}

		if (acks >= QUORUM) {
			log.info("Broadcast acknowledged by " + acks + "/" + targets.size() + " nodes — characterId="
					+ record.getCharacterId() + " sequence=" + record.getSequence());
		} else {
			log.warning("Broadcast under-quorum (" + acks + "/" + targets.size() + ") — queuing for retry. characterId="
					+ record.getCharacterId());
			retryQueue.add(record);
		}

		flushRetryQueue(targets);
	}

	private void flushRetryQueue(List<RegistryNode> targets) {
		if (retryQueue.isEmpty())
			return;

		List<SnapshotRecord> toRetry = new ArrayList<>(retryQueue);
		retryQueue.clear();

		for (SnapshotRecord record : toRetry) {
			boolean pushed = false;
			for (RegistryNode node : targets) {
				if (transport.push(node, record)) {
					pushed = true;
					break;
				}
			}
			if (!pushed) {
				retryQueue.add(record);
				log.warning("Retry failed — re-queued characterId=" + record.getCharacterId());
			} else {
				log.info("Retry succeeded — characterId=" + record.getCharacterId());
			}
		}
	}

	private List<RegistryNode> availableNodes() {
		List<RegistryNode> available = new ArrayList<>();
		for (RegistryNode node : nodes) {
			if (node.isAvailable())
				available.add(node);
		}
		Collections.shuffle(available);
		return available;
	}

	public void addNode(String nodeUrl) {
		nodes.add(new RegistryNode(nodeUrl));
		log.info("Added node: " + nodeUrl);
	}

	public void logNodeStatus() {
		for (RegistryNode node : nodes) {
			log.info("Node status: " + node);
		}
	}

	public int retryQueueSize() {
		return retryQueue.size();
	}

	public void shutdown() {
		retryScheduler.shutdown();
		if (!retryQueue.isEmpty()) {
			log.info("Flushing " + retryQueue.size() + " queued snapshots on shutdown...");
			flushRetryQueue(availableNodes());
		}
		broadcastPool.shutdown();
	}
}
