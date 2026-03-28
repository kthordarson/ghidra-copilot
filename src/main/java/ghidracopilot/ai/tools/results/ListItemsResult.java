package ghidracopilot.ai.tools.results;

import java.util.List;
import java.util.Objects;

/**
 * Shared structured payload for list-style tool results.
 */
public record ListItemsResult(String kind, int count, boolean truncated, List<Item> items) {

	public ListItemsResult {
		kind = Objects.requireNonNull(kind, "kind must not be null");
		items = items == null ? List.of() : List.copyOf(items);
	}

	public record Item(String display, String name, String address) {

		public Item {
			display = Objects.requireNonNullElse(display, "");
		}
	}

	public String summary() {
		String singular = singularize(kind);
		String plural = pluralize(singular);
		List<String> labels = items.stream()
			.map(item -> item.display() != null && !item.display().isBlank() ? item.display() : item.name())
			.filter(label -> label != null && !label.isBlank())
			.toList();

		if (count == 0 || labels.isEmpty()) {
			return count == 0 ? "No " + plural : count + " " + (count == 1 ? singular : plural);
		}

		int shown = Math.min(labels.size(), 2);
		String joined = String.join(", ", labels.subList(0, shown));
		if (count > shown || truncated) {
			joined += ", ...";
		}
		return truncate(count + " " + (count == 1 ? singular : plural) + ": " + joined, 96);
	}

	private static String singularize(String plural) {
		if (plural == null || plural.isBlank()) {
			return "item";
		}
		String word = plural.toLowerCase();
		if (word.endsWith("sses")) return word.substring(0, word.length() - 2);
		if (word.endsWith("ies") && word.length() > 3) return word.substring(0, word.length() - 3) + "y";
		if (word.endsWith("s") && word.length() > 1) return word.substring(0, word.length() - 1);
		return word;
	}

	private static String pluralize(String singular) {
		if (singular == null || singular.isBlank()) {
			return "items";
		}
		String word = singularize(singular);
		if (word.endsWith("y") && word.length() > 1) return word.substring(0, word.length() - 1) + "ies";
		if (word.endsWith("s")) return word + "es";
		return word + "s";
	}

	private static String truncate(String value, int maxLen) {
		if (value == null) return null;
		return value.length() > maxLen ? value.substring(0, maxLen - 3) + "..." : value;
	}
}
