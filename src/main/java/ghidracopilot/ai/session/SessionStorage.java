package ghidracopilot.ai.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ghidra.framework.Application;
import ghidra.util.Msg;

/**
 * Persists {@link ChatSession}s as JSON files in the user's Ghidra settings directory.
 * <p>
 * Storage layout:
 * <pre>
 *   ~/.ghidra/{version}/ghidracopilot/sessions/{id}.json
 * </pre>
 */
public final class SessionStorage {

	private static final String SESSIONS_DIR = "sessions";
	private static final int MAX_SESSIONS = 50;
	private static final ObjectMapper MAPPER = createMapper();

	private SessionStorage() {}

	private static ObjectMapper createMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
			.withFieldVisibility(JsonAutoDetect.Visibility.ANY)
			.withGetterVisibility(JsonAutoDetect.Visibility.NONE)
			.withSetterVisibility(JsonAutoDetect.Visibility.NONE));
		mapper.enable(SerializationFeature.INDENT_OUTPUT);
		return mapper;
	}

	/** Save a session to disk. */
	public static void save(ChatSession session) {
		if (session == null || session.getId() == null) {
			return;
		}
		try {
			session.deriveTitle();
			Path dir = ensureSessionDir();
			Path file = dir.resolve(session.getId() + ".json");
			String json = MAPPER.writeValueAsString(session);
			Files.writeString(file, json, StandardCharsets.UTF_8);
			pruneOldSessions(dir);
		} catch (Exception ex) {
			Msg.error(SessionStorage.class, "Failed to save session: " + ex.getMessage(), ex);
		}
	}

	/** Load a session by ID. Returns null if not found or on error. */
	public static ChatSession load(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return null;
		}
		try {
			Path file = sessionDir().resolve(sessionId + ".json");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			String json = Files.readString(file, StandardCharsets.UTF_8);
			return MAPPER.readValue(json, ChatSession.class);
		} catch (Exception ex) {
			Msg.error(SessionStorage.class, "Failed to load session " + sessionId, ex);
			return null;
		}
	}

	/** List all saved sessions, most recent first. */
	public static List<SessionSummary> listSessions() {
		List<SessionSummary> summaries = new ArrayList<>();
		Path dir = sessionDir();
		if (!Files.isDirectory(dir)) {
			return summaries;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : stream) {
				try {
					ChatSession session = MAPPER.readValue(
						Files.readString(file, StandardCharsets.UTF_8), ChatSession.class);
					summaries.add(new SessionSummary(
						session.getId(),
						session.getTitle(),
						session.getUpdatedAt(),
						session.getProviderId(),
						session.getProgramName(),
						session.getMessages().size()));
				} catch (Exception ex) {
					Msg.warn(SessionStorage.class, "Skipping corrupt session file: " + file);
				}
			}
		} catch (IOException ex) {
			Msg.error(SessionStorage.class, "Failed to list sessions", ex);
		}
		summaries.sort(Comparator.comparingLong(SessionSummary::updatedAt).reversed());
		return summaries;
	}

	/** Delete a session by ID. */
	public static boolean delete(String sessionId) {
		if (sessionId == null) {
			return false;
		}
		try {
			Path file = sessionDir().resolve(sessionId + ".json");
			return Files.deleteIfExists(file);
		} catch (IOException ex) {
			Msg.error(SessionStorage.class, "Failed to delete session " + sessionId, ex);
			return false;
		}
	}

	/** Remove oldest sessions if we exceed MAX_SESSIONS. */
	private static void pruneOldSessions(Path dir) {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
			List<Path> files = new ArrayList<>();
			stream.forEach(files::add);
			if (files.size() <= MAX_SESSIONS) {
				return;
			}
			files.sort(Comparator.comparingLong(p -> {
				try { return Files.getLastModifiedTime(p).toMillis(); }
				catch (IOException e) { return 0L; }
			}));
			int toRemove = files.size() - MAX_SESSIONS;
			for (int i = 0; i < toRemove; i++) {
				Files.deleteIfExists(files.get(i));
			}
		} catch (IOException ex) {
			Msg.warn(SessionStorage.class, "Failed to prune old sessions: " + ex.getMessage());
		}
	}

	private static Path ensureSessionDir() throws IOException {
		Path dir = sessionDir();
		Files.createDirectories(dir);
		return dir;
	}

	private static Path sessionDir() {
		return Application.getUserSettingsDirectory().toPath()
			.resolve("ghidracopilot")
			.resolve(SESSIONS_DIR);
	}

	/** Lightweight summary for the session list UI. */
	public record SessionSummary(
		String id,
		String title,
		long updatedAt,
		String providerId,
		String programName,
		int messageCount
	) {}

	// ── Prompt history persistence ──────────────────────────────────────

	private static final String HISTORY_FILE = "prompt_history.json";
	private static final int MAX_HISTORY = 200;

	/** Load prompt history from disk. Returns empty list on failure. */
	public static List<String> loadPromptHistory() {
		try {
			Path file = copilotDir().resolve(HISTORY_FILE);
			if (!Files.exists(file)) return new ArrayList<>();
			String json = Files.readString(file, StandardCharsets.UTF_8);
			String[] entries = MAPPER.readValue(json, String[].class);
			return new ArrayList<>(List.of(entries));
		}
		catch (Exception ex) {
			Msg.warn(SessionStorage.class, "Failed to load prompt history: " + ex.getMessage());
			return new ArrayList<>();
		}
	}

	/** Save prompt history to disk, keeping at most MAX_HISTORY entries. */
	public static void savePromptHistory(List<String> history) {
		if (history == null) return;
		try {
			List<String> toSave = history.size() > MAX_HISTORY
				? history.subList(history.size() - MAX_HISTORY, history.size())
				: history;
			Path dir = copilotDir();
			Files.createDirectories(dir);
			String json = MAPPER.writeValueAsString(toSave);
			Files.writeString(dir.resolve(HISTORY_FILE), json, StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			Msg.warn(SessionStorage.class, "Failed to save prompt history: " + ex.getMessage());
		}
	}

	private static Path copilotDir() {
		return Application.getUserSettingsDirectory().toPath().resolve("ghidracopilot");
	}
}
