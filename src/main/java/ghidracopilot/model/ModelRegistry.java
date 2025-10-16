package ghidracopilot.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import ghidracopilot.ai.AiProvider;

/**
 * Global registry of large language models available to Ghidra Copilot.
 */
public final class ModelRegistry {

	private static final Map<String, ModelEntry> MODELS_BY_KEY = new LinkedHashMap<>();
	private static final Comparator<ModelEntry> DISPLAY_COMPARATOR = Comparator
			.comparing((ModelEntry entry) -> entry.provider().displayName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(ModelEntry::displayName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(ModelEntry::identifier);

	private static String defaultModelKey;

	private ModelRegistry() {
		// utility
	}

	public static synchronized void replaceAll(Collection<ModelEntry> models) {
		MODELS_BY_KEY.clear();
		if (models != null) {
			for (ModelEntry entry : models) {
				MODELS_BY_KEY.put(entry.key(), entry);
			}
		}
		if (!MODELS_BY_KEY.containsKey(defaultModelKey)) {
			defaultModelKey = MODELS_BY_KEY.keySet().stream().findFirst().orElse(null);
		}
	}

	public static synchronized void add(ModelEntry entry) {
		MODELS_BY_KEY.put(entry.key(), entry);
	}

	public static synchronized List<ModelEntry> allModels() {
		List<ModelEntry> sorted = new ArrayList<>(MODELS_BY_KEY.values());
		sorted.sort(DISPLAY_COMPARATOR);
		return Collections.unmodifiableList(sorted);
	}

	public static synchronized Optional<ModelEntry> findByKey(String key) {
		if (key == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(MODELS_BY_KEY.get(key));
	}

	public static synchronized void setDefaultModelKey(String key) {
		if (key == null || MODELS_BY_KEY.containsKey(key)) {
			defaultModelKey = key;
		}
	}

	public static synchronized Optional<ModelEntry> defaultModel() {
		if (defaultModelKey == null) {
			return Optional.empty();
		}
		return findByKey(defaultModelKey);
	}

	public static String keyFor(AiProvider provider, String identifier) {
		return provider.id() + ":" + identifier;
	}

	public record ModelEntry(AiProvider provider, String identifier, String displayName) {

		public ModelEntry {
			Objects.requireNonNull(provider, "provider");
			if (identifier == null || identifier.isBlank()) {
				throw new IllegalArgumentException("identifier must not be null or blank");
			}
			identifier = identifier.trim();
			displayName = (displayName != null && !displayName.isBlank()) ? displayName.trim() : identifier;
		}

		public String key() {
			return keyFor(provider, identifier);
		}
	}
}
