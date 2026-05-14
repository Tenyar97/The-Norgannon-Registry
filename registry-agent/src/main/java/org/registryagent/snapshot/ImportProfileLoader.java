package org.registryagent.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ImportProfileLoader {

    private static final Logger log = Logger.getLogger(ImportProfileLoader.class.getName());

    private final Path profileDir;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, ImportProfile> cache = new ConcurrentHashMap<>();

    public ImportProfileLoader(Path profileDir) {
        this.profileDir = profileDir;
        log.info("ImportProfileLoader: profiles directory = " + profileDir.toAbsolutePath());
    }

    public ImportProfileLoader() {
        this(Path.of("profiles"));
    }

    public ImportProfile load(String name) {
        if (name == null || name.isBlank()) return null;
        return cache.computeIfAbsent(name, this::loadFromDisk);
    }

    public ImportProfile loadOrPermissive(String name) {
        if (name == null || name.isBlank()) return ImportProfile.permissive();
        ImportProfile profile = load(name);
        if (profile == null) {
            log.warning("ImportProfileLoader: profile '" + name
                        + "' not found — falling back to permissive defaults");
            return ImportProfile.permissive();
        }
        return profile;
    }

    public void invalidate(String name) {
        cache.remove(name);
    }

    public void invalidateAll() {
        cache.clear();
    }


    private ImportProfile loadFromDisk(String name) {
        File file = profileDir.resolve(name + ".json").toFile();
        if (!file.exists()) {
            log.warning("ImportProfileLoader: profile file not found: " + file.getAbsolutePath());
            return null;
        }
        try {
            ImportProfile profile = mapper.readValue(file, ImportProfile.class);
            if (profile.getName() == null || profile.getName().equals("default")) {
                profile.setName(name);
            }
            log.info("ImportProfileLoader: loaded profile '" + name + "' — " + profile);
            return profile;
        } catch (IOException e) {
            log.severe("ImportProfileLoader: failed to parse profile '" + name + "': " + e.getMessage());
            return null;
        }
    }
}
