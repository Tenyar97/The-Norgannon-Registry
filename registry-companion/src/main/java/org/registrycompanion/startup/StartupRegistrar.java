package org.registrycompanion.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class StartupRegistrar {

	private static final Logger log = Logger.getLogger(StartupRegistrar.class.getName());

	private static final String REG_KEY = "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run";
	private static final String REG_VALUE = "RegistryCompanion";

	public boolean register() {
		String command = resolveLaunchCommand();
		if (command == null) {
			log.warning("Could not resolve launch command — startup registration skipped");
			return false;
		}

		try {
			Process process = new ProcessBuilder("reg", "add", REG_KEY, "/v", REG_VALUE, "/t", "REG_SZ", "/d", command,
					"/f").start();

			int exit = process.waitFor();
			if (exit == 0) {
				log.info("Startup registration set: " + command);
				return true;
			}

			String error = new String(process.getErrorStream().readAllBytes());
			log.warning("reg add failed (exit=" + exit + "): " + error);
			return false;

		} catch (Exception e) {
			log.warning("Failed to register startup: " + e.getMessage());
			return false;
		}
	}

	public boolean unregister() {
		try {
			Process process = new ProcessBuilder("reg", "delete", REG_KEY, "/v", REG_VALUE, "/f").start();

			int exit = process.waitFor();
			if (exit == 0) {
				log.info("Startup registration removed");
				return true;
			}

			String output = new String(process.getInputStream().readAllBytes());
			if (output.toLowerCase().contains("unable to find")) {
				log.fine("Startup key did not exist — nothing to remove");
				return true;
			}

			log.warning("reg delete failed (exit=" + exit + ")");
			return false;

		} catch (Exception e) {
			log.warning("Failed to unregister startup: " + e.getMessage());
			return false;
		}
	}

	public boolean isRegistered() {
		try {
			Process process = new ProcessBuilder("reg", "query", REG_KEY, "/v", REG_VALUE).start();

			int exit = process.waitFor();
			return exit == 0;

		} catch (Exception e) {
			log.fine("isRegistered check failed: " + e.getMessage());
			return false;
		}
	}

	public String getRegisteredCommand() {
		try {
			Process process = new ProcessBuilder("reg", "query", REG_KEY, "/v", REG_VALUE).start();

			String output = new String(process.getInputStream().readAllBytes());
			process.waitFor();

			for (String line : output.split("\n")) {
				if (line.contains(REG_VALUE) && line.contains("REG_SZ")) {
					String[] parts = line.trim().split("\\s{2,}");
					if (parts.length >= 3) {
						return parts[parts.length - 1].trim();
					}
				}
			}

		} catch (Exception e) {
			log.fine("getRegisteredCommand failed: " + e.getMessage());
		}
		return null;
	}

	public boolean toggle() {
		if (isRegistered()) {
			unregister();
			return false;
		} else {
			register();
			return true;
		}
	}

	String resolveLaunchCommand() {
		Path selfDir = getSelfDirectory();
		if (selfDir == null)
			return null;

		Path exePath = selfDir.resolve("RegistryCompanion.exe");
		if (Files.exists(exePath)) {
			return "\"" + exePath.toAbsolutePath() + "\"";
		}

		Path jarPath = findJar(selfDir);
		if (jarPath != null) {
			String javaw = resolveJavaw();
			return "\"" + javaw + "\" -jar \"" + jarPath.toAbsolutePath() + "\"";
		}

		log.warning("Could not find RegistryCompanion.exe or any registry-companion JAR in: " + selfDir);
		return null;
	}

	private Path getSelfDirectory() {
		try {
			String location = StartupRegistrar.class.getProtectionDomain().getCodeSource().getLocation().toURI()
					.getPath();

			if (location.startsWith("/") && location.length() > 2 && location.charAt(2) == ':') {
				location = location.substring(1);
			}

			Path codePath = Path.of(location);

			return Files.isDirectory(codePath) ? codePath : codePath.getParent();

		} catch (Exception e) {
			log.warning("Could not determine self directory: " + e.getMessage());
			return null;
		}
	}

	private Path findJar(Path dir) {
		try (var stream = Files.list(dir)) {
			return stream.filter(p -> {
				String name = p.getFileName().toString().toLowerCase();
				return name.endsWith(".jar") && (name.contains("shaded") || name.contains("registry-companion"));
			}).findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private String resolveJavaw() {
		String javaHome = System.getProperty("java.home");
		if (javaHome != null) {
			Path javaw = Path.of(javaHome, "bin", "javaw.exe");
			if (Files.exists(javaw)) {
				return javaw.toAbsolutePath().toString();
			}
		}
		return "javaw";
	}
}
