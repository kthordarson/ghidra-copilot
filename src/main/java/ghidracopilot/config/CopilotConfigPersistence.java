package ghidracopilot.config;

import static ghidracopilot.CopilotOptions.DEFAULT_ANTHROPIC_MODEL;
import static ghidracopilot.CopilotOptions.DEFAULT_OLLAMA_BASE_URL;
import static ghidracopilot.CopilotOptions.DEFAULT_OLLAMA_MODEL;
import static ghidracopilot.CopilotOptions.DEFAULT_OPENAI_MODEL;
import static ghidracopilot.CopilotOptions.DEFAULT_PROVIDER;
import static ghidracopilot.CopilotOptions.OPTION_ANTHROPIC_API_KEY;
import static ghidracopilot.CopilotOptions.OPTION_ANTHROPIC_MODEL;
import static ghidracopilot.CopilotOptions.OPTION_AZURE_API_KEY;
import static ghidracopilot.CopilotOptions.OPTION_AZURE_DEPLOYMENT;
import static ghidracopilot.CopilotOptions.OPTION_AZURE_ENDPOINT;
import static ghidracopilot.CopilotOptions.OPTION_AZURE_MODEL;
import static ghidracopilot.CopilotOptions.OPTION_OLLAMA_BASE_URL;
import static ghidracopilot.CopilotOptions.OPTION_OLLAMA_MODEL;
import static ghidracopilot.CopilotOptions.OPTION_OPENAI_API_KEY;
import static ghidracopilot.CopilotOptions.OPTION_OPENAI_BASE_URL;
import static ghidracopilot.CopilotOptions.OPTION_OPENAI_MODEL;
import static ghidracopilot.CopilotOptions.OPTION_PROVIDER;
import static ghidracopilot.CopilotOptions.OPTION_SYSTEM_PROMPT;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import ghidra.framework.Application;
import ghidra.framework.options.ToolOptions;
import ghidra.util.Msg;
import ghidracopilot.CopilotOptions;

/**
 * Persists Copilot settings outside the tool configuration so they survive restarts.
 */
public final class CopilotConfigPersistence {

	private static final String SETTINGS_DIR = "ghidracopilot";
	private static final String SETTINGS_FILE = "settings.properties";

	private CopilotConfigPersistence() {
		// utility class
	}

	/**
	 * Loads settings from disk, returning them as a {@link Properties} instance. The returned
	 * properties will be empty when no persisted settings exist.
	 */
	public static Properties load() {
		Properties properties = new Properties();
		Path configFile = configFile();
		if (!Files.isRegularFile(configFile)) {
			return properties;
		}
		try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (IOException ex) {
			Msg.error(CopilotConfigPersistence.class, "Failed to load Copilot settings", ex);
			properties.clear();
		}
		return properties;
	}

	/**
	 * Writes the current {@link ToolOptions} values to disk so they can be restored later.
	 *
	 * @param options the tool options supplying the data to persist
	 */
	public static void persistFrom(ToolOptions options) {
		if (options == null) {
			return;
		}

		Properties properties = new Properties();
		properties.setProperty(OPTION_PROVIDER,
			valueOrDefault(options.getString(OPTION_PROVIDER, DEFAULT_PROVIDER), DEFAULT_PROVIDER));
		properties.setProperty(OPTION_SYSTEM_PROMPT,
			valueOrDefault(options.getString(OPTION_SYSTEM_PROMPT, CopilotOptions.defaultSystemPrompt()),
				CopilotOptions.defaultSystemPrompt()));
		properties.setProperty(OPTION_OPENAI_API_KEY,
			valueOrEmpty(options.getString(OPTION_OPENAI_API_KEY, "")));
		properties.setProperty(OPTION_OPENAI_BASE_URL,
			valueOrEmpty(options.getString(OPTION_OPENAI_BASE_URL, "")));
		properties.setProperty(OPTION_OPENAI_MODEL,
			valueOrDefault(options.getString(OPTION_OPENAI_MODEL, DEFAULT_OPENAI_MODEL),
				DEFAULT_OPENAI_MODEL));
		properties.setProperty(OPTION_AZURE_API_KEY,
			valueOrEmpty(options.getString(OPTION_AZURE_API_KEY, "")));
		properties.setProperty(OPTION_AZURE_ENDPOINT,
			valueOrEmpty(options.getString(OPTION_AZURE_ENDPOINT, "")));
		properties.setProperty(OPTION_AZURE_DEPLOYMENT,
			valueOrEmpty(options.getString(OPTION_AZURE_DEPLOYMENT, "")));
		properties.setProperty(OPTION_AZURE_MODEL,
			valueOrEmpty(options.getString(OPTION_AZURE_MODEL, "")));
		properties.setProperty(OPTION_ANTHROPIC_API_KEY,
			valueOrEmpty(options.getString(OPTION_ANTHROPIC_API_KEY, "")));
		properties.setProperty(OPTION_ANTHROPIC_MODEL,
			valueOrDefault(options.getString(OPTION_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL),
				DEFAULT_ANTHROPIC_MODEL));
		properties.setProperty(OPTION_OLLAMA_BASE_URL,
			valueOrDefault(options.getString(OPTION_OLLAMA_BASE_URL, DEFAULT_OLLAMA_BASE_URL),
				DEFAULT_OLLAMA_BASE_URL));
		properties.setProperty(OPTION_OLLAMA_MODEL,
			valueOrDefault(options.getString(OPTION_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL),
				DEFAULT_OLLAMA_MODEL));

		try {
			Path configFile = ensureConfigFile();
			try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
				properties.store(writer, "Ghidra Copilot settings");
			}
		}
		catch (IOException ex) {
			Msg.error(CopilotConfigPersistence.class, "Failed to persist Copilot settings", ex);
		}
	}

	private static Path ensureConfigFile() throws IOException {
		Path directory = configDirectory();
		Files.createDirectories(directory);
		return directory.resolve(SETTINGS_FILE);
	}

	private static Path configFile() {
		return configDirectory().resolve(SETTINGS_FILE);
	}

	private static Path configDirectory() {
		return Application.getUserSettingsDirectory().toPath().resolve(SETTINGS_DIR);
	}

	private static String valueOrEmpty(String value) {
		return value != null ? value : "";
	}

	private static String valueOrDefault(String value, String defaultValue) {
		return value != null ? value : (defaultValue != null ? defaultValue : "");
	}
}
