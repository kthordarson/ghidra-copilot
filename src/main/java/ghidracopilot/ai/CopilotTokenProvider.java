package ghidracopilot.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ghidra.util.Msg;

/**
 * Provides a GitHub OAuth token for use with the Copilot API by running
 * {@code gh auth token}. The token from {@code gh} is used directly as a
 * Bearer token against {@code api.githubcopilot.com} — no exchange step
 * is needed, matching how the official Copilot CLI authenticates.
 */
public final class CopilotTokenProvider {

	private static final String MODELS_ENDPOINT = "https://api.githubcopilot.com/models";
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private String cachedToken;

	/**
	 * Returns the GitHub OAuth token obtained from {@code gh auth token}.
	 * Result is cached for the lifetime of this provider instance.
	 */
	public synchronized String getToken() {
		if (cachedToken != null) {
			return cachedToken;
		}
		cachedToken = runGhAuthToken();
		Msg.info(this, "Copilot token obtained from gh CLI");
		return cachedToken;
	}

	/**
	 * Clears the cached token so the next call to {@link #getToken()} will
	 * re-run {@code gh auth token}. Useful after an auth failure.
	 */
	public synchronized void clearCache() {
		cachedToken = null;
	}

	/**
	 * A model available through the Copilot API.
	 */
	public record CopilotModel(String id, String name, String vendor, String category,
			int contextWindow, int maxOutput, boolean pickerEnabled, boolean preview) {

		/** Sort rank: powerful=0, versatile=1, lightweight=2, unknown=3. */
		int categoryRank() {
			return switch (category) {
				case "powerful" -> 0;
				case "versatile" -> 1;
				case "lightweight" -> 2;
				default -> 3;
			};
		}

		/** Display label for the dropdown: "model-id  (Vendor)" */
		public String displayLabel() {
			String label = id;
			if (vendor != null && !vendor.isBlank()) {
				label += "  (" + vendor + ")";
			}
			if (preview) {
				label += " [preview]";
			}
			return label;
		}
	}

	/**
	 * Fetch chat-capable models, sorted by tier (powerful → versatile → lightweight)
	 * then by context window descending.
	 */
	public List<CopilotModel> fetchAvailableModels() {
		String token = getToken();
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(MODELS_ENDPOINT))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				.header("Copilot-Integration-Id", "ghidra-copilot")
				.header("User-Agent", "GhidraCopilot/1.0")
				.GET()
				.timeout(HTTP_TIMEOUT)
				.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401) {
				clearCache();
				Msg.warn(this, "Copilot token was rejected (401). Re-authenticate with: gh auth login");
				return List.of();
			}
			if (response.statusCode() != 200) {
				Msg.warn(this, "Failed to fetch Copilot models (HTTP " + response.statusCode() + "): "
					+ response.body());
				return List.of();
			}

			JsonNode root = MAPPER.readTree(response.body());
			JsonNode data = root.has("data") ? root.get("data") : root;
			List<CopilotModel> models = new ArrayList<>();
			if (data.isArray()) {
				for (JsonNode node : data) {
					CopilotModel model = parseModel(node);
					if (model != null) {
						models.add(model);
					}
				}
			}
			// Sort: powerful first, then versatile, then lightweight; within tier by context desc
			models.sort(Comparator.comparingInt(CopilotModel::categoryRank)
				.thenComparing(Comparator.comparingInt(CopilotModel::contextWindow).reversed())
				.thenComparing(CopilotModel::id));
			return models;
		}
		catch (Exception ex) {
			Msg.warn(this, "Failed to fetch Copilot model list: " + ex.getMessage());
			return List.of();
		}
	}

	static String runGhAuthToken() {
		try {
			ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			String output;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				output = reader.readLine();
			}
			int exitCode = process.waitFor();
			if (exitCode != 0 || output == null || output.isBlank()) {
				throw new IllegalStateException(
					"'gh auth token' failed (exit " + exitCode + "). " +
					"Make sure the GitHub CLI is installed and you're logged in: gh auth login");
			}
			return output.trim();
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException(
				"Could not run 'gh auth token'. Install the GitHub CLI: https://cli.github.com", ex);
		}
	}

	private static CopilotModel parseModel(JsonNode node) {
		String id = node.has("id") ? node.get("id").asText() : null;
		if (id == null || id.isBlank()) {
			return null;
		}
		JsonNode caps = node.get("capabilities");
		if (caps != null && caps.has("type") && !"chat".equals(caps.get("type").asText())) {
			return null;
		}
		String category = node.has("model_picker_category")
			? node.get("model_picker_category").asText() : "";
		JsonNode endpoints = node.get("supported_endpoints");
		boolean supportsChatCompletions = false;
		if (endpoints != null && endpoints.isArray()) {
			for (JsonNode ep : endpoints) {
				if ("/chat/completions".equals(ep.asText())) {
					supportsChatCompletions = true;
					break;
				}
			}
		}
		if (category.isBlank() && !supportsChatCompletions) {
			return null;
		}
		String name = node.has("name") ? node.get("name").asText() : id;
		String vendor = node.has("vendor") ? node.get("vendor").asText() : "";
		boolean pickerEnabled = node.has("model_picker_enabled") && node.get("model_picker_enabled").asBoolean();
		boolean preview = node.has("preview") && node.get("preview").asBoolean();
		int contextWindow = 0;
		int maxOutput = 0;
		if (caps != null && caps.has("limits")) {
			JsonNode limits = caps.get("limits");
			contextWindow = limits.has("max_context_window_tokens") ? limits.get("max_context_window_tokens").asInt() : 0;
			maxOutput = limits.has("max_output_tokens") ? limits.get("max_output_tokens").asInt() : 0;
		}
		return new CopilotModel(id, name, vendor, category, contextWindow, maxOutput, pickerEnabled, preview);
	}
}
