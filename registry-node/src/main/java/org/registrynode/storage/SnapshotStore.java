package org.registrynode.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.registrynode.model.SnapshotRecord;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public class SnapshotStore {

	private static final Logger log = Logger.getLogger(SnapshotStore.class.getName());

	private final Path dataDir;
	private final ObjectMapper mapper;

	private final ConcurrentHashMap<String, SnapshotRecord> cache = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

	public SnapshotStore(Path dataDir) throws IOException {
		this.dataDir = dataDir;
		Files.createDirectories(dataDir);

		this.mapper = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true)
				.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		log.info("SnapshotStore initialised — dataDir=" + dataDir);
	}

	public SnapshotRecord get(String characterId) {
		SnapshotRecord cached = cache.get(characterId);
		if (cached != null)
			return cached;

		ReadWriteLock lock = getLock(characterId);
		lock.readLock().lock();
		try {
			Path file = filePath(characterId);
			if (!Files.exists(file))
				return null;

			SnapshotRecord record = mapper.readValue(file.toFile(), SnapshotRecord.class);
			cache.put(characterId, record);
			return record;

		} catch (IOException e) {
			log.warning("Failed to read record for " + characterId + ": " + e.getMessage());
			return null;
		} finally {
			lock.readLock().unlock();
		}
	}

	public boolean exists(String characterId) {
		return cache.containsKey(characterId) || Files.exists(filePath(characterId));
	}

	public long currentSequence(String characterId) {
		SnapshotRecord record = get(characterId);
		return record != null ? record.getSequence() : 0L;
	}

	public StoreResult put(SnapshotRecord record) {
		String characterId = record.getCharacterId();

		ReadWriteLock lock = getLock(characterId);
		lock.writeLock().lock();
		try {
			long currentSeq = currentSequence(characterId);

			if (record.getSequence() <= currentSeq) {
				log.fine("Stale record ignored — characterId=" + characterId + " incoming=" + record.getSequence()
						+ " current=" + currentSeq);
				return StoreResult.STALE;
			}

			Path file = filePath(characterId);
			Path tempFile = dataDir.resolve(characterId + ".tmp");

			mapper.writeValue(tempFile.toFile(), record);
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

			cache.put(characterId, record);

			log.info("Record stored — characterId=" + characterId + " sequence=" + record.getSequence());

			return StoreResult.STORED;

		} catch (IOException e) {
			log.severe("Failed to write record for " + characterId + ": " + e.getMessage());
			return StoreResult.FAILED;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public List<String> listCharacterIds() {
		List<String> ids = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.json")) {
			for (Path path : stream) {
				String filename = path.getFileName().toString();
				ids.add(filename.substring(0, filename.length() - 5));
			}
		} catch (IOException e) {
			log.warning("Failed to list data directory: " + e.getMessage());
		}
		return ids;
	}

	public int size() {
		return listCharacterIds().size();
	}

	public void warmup() {
		List<String> ids = listCharacterIds();
		int loaded = 0;

		for (String id : ids) {
			SnapshotRecord record = get(id);
			if (record != null)
				loaded++;
		}

		log.info("Warmup complete — " + loaded + " records loaded into cache");
	}

	private Path filePath(String characterId) {
		String safe = characterId.replaceAll("[^a-zA-Z0-9\\-]", "_");
		return dataDir.resolve(safe + ".json");
	}

	private ReadWriteLock getLock(String characterId) {
		return locks.computeIfAbsent(characterId, k -> new ReentrantReadWriteLock());
	}

	public enum StoreResult {
		STORED, STALE, FAILED
	}
}
