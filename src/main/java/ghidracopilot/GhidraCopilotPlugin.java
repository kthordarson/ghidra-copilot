/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidracopilot;

import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import ghidra.app.ExamplesPluginPackage;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import static ghidracopilot.CopilotOptions.DEFAULT_ANTHROPIC_MODEL;
import static ghidracopilot.CopilotOptions.DEFAULT_OLLAMA_BASE_URL;
import static ghidracopilot.CopilotOptions.DEFAULT_OLLAMA_MODEL;
import static ghidracopilot.CopilotOptions.DEFAULT_OPENAI_MODEL;
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
import static ghidracopilot.CopilotOptions.OPTIONS_CATEGORY;

import ghidra.framework.options.OptionType;
import ghidra.framework.options.OptionsChangeListener;
import ghidra.framework.options.ToolOptions;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.util.HelpLocation;
import ghidracopilot.ai.AiProvider;
import ghidracopilot.model.ModelRegistry;
import ghidracopilot.model.ModelRegistry.ModelEntry;
import ghidracopilot.ai.ChatSettings;
import ghidracopilot.ai.SpringAiChatServiceFactory;
import ghidracopilot.ui.CopilotProvider;
import ghidracopilot.ui.CopilotSettingsDialog;
import org.springframework.util.StringUtils;

/**
 * Provide class-level documentation that describes what this plugin does.
 */
//@formatter:off
@PluginInfo(
	status = PluginStatus.STABLE,
	packageName = ExamplesPluginPackage.NAME,
	category = PluginCategoryNames.EXAMPLES,
	shortDescription = "Ghidra Copilot Chat",
	description = "Copilot to help with reverse engineering tasks."
)
//@formatter:on
public class GhidraCopilotPlugin extends ProgramPlugin implements OptionsChangeListener {

	private CopilotProvider provider;
	private ToolOptions toolOptions;
	private DockingAction openSettingsAction;

	/**
	 * Plugin constructor.
	 * 
	 * @param tool The plugin tool that this plugin is added to.
	 */
	public GhidraCopilotPlugin(PluginTool tool) {
		super(tool);

		toolOptions = tool.getOptions(OPTIONS_CATEGORY);
		registerOptions(toolOptions);
		toolOptions.addOptionsChangeListener(this);

		String pluginName = getName();
		provider = new CopilotProvider(this, pluginName);
		provider.addToTool();
		provider.setVisible(true);

		String topicName = this.getClass().getPackage().getName();
		String anchorName = "HelpAnchor";
		provider.setHelpLocation(new HelpLocation(topicName, anchorName));

		createSettingsMenuAction();

		SwingUtilities.invokeLater(this::applySettings);
	}

	@Override
	public void init() {
		super.init();

		// Acquire services if necessary
	}

	@Override
	protected void dispose() {
		if (toolOptions != null) {
			toolOptions.removeOptionsChangeListener(this);
		}
		if (openSettingsAction != null) {
			tool.removeAction(openSettingsAction);
		}
		super.dispose();
	}

	@Override
	public void optionsChanged(ToolOptions options, String optionName, Object oldValue, Object newValue) {
		if (options != toolOptions) {
			return;
		}
		applySettings();
	}

	private void registerOptions(ToolOptions options) {
		options.registerOption(OPTION_PROVIDER, OptionType.STRING_TYPE, CopilotOptions.DEFAULT_PROVIDER, null,
			"Large language model provider used for Copilot responses.");
		options.registerOption(OPTION_SYSTEM_PROMPT, OptionType.STRING_TYPE,
			CopilotOptions.defaultSystemPrompt(), null,
			"System instruction that is prepended to every request.");

		options.registerOption(OPTION_OPENAI_API_KEY, OptionType.STRING_TYPE, "", null,
			"Secret key issued by OpenAI.");
		options.registerOption(OPTION_OPENAI_BASE_URL, OptionType.STRING_TYPE, "", null,
			"Optional override for the OpenAI API base URL.");
		options.registerOption(OPTION_OPENAI_MODEL, OptionType.STRING_TYPE, DEFAULT_OPENAI_MODEL, null,
			"OpenAI chat model identifier.");

		options.registerOption(OPTION_AZURE_API_KEY, OptionType.STRING_TYPE, "", null,
			"Azure OpenAI API key.");
		options.registerOption(OPTION_AZURE_ENDPOINT, OptionType.STRING_TYPE, "", null,
			"Azure OpenAI endpoint URL.");
		options.registerOption(OPTION_AZURE_DEPLOYMENT, OptionType.STRING_TYPE, "", null,
			"Azure OpenAI deployment name.");
		options.registerOption(OPTION_AZURE_MODEL, OptionType.STRING_TYPE, "", null,
			"Optional Azure OpenAI model identifier.");

		options.registerOption(OPTION_ANTHROPIC_API_KEY, OptionType.STRING_TYPE, "", null,
			"Anthropic API key.");
		options.registerOption(OPTION_ANTHROPIC_MODEL, OptionType.STRING_TYPE, DEFAULT_ANTHROPIC_MODEL, null,
			"Anthropic Claude model identifier.");

		options.registerOption(OPTION_OLLAMA_BASE_URL, OptionType.STRING_TYPE, DEFAULT_OLLAMA_BASE_URL, null,
			"Ollama server base URL.");
		options.registerOption(OPTION_OLLAMA_MODEL, OptionType.STRING_TYPE, DEFAULT_OLLAMA_MODEL, null,
			"Ollama model to load.");
	}

	private void createSettingsMenuAction() {
		openSettingsAction = new DockingAction("Ghidra Copilot Settings", getName()) {
			@Override
			public void actionPerformed(ActionContext context) {
				showSettingsDialog();
			}
		};
		openSettingsAction.setDescription("Configure the AI provider and credentials used by Ghidra Copilot.");
		openSettingsAction.setMenuBarData(new MenuData(
			new String[] { "Tools", "Ghidra Copilot Settings..." }, (String) null));
		openSettingsAction.setHelpLocation(new HelpLocation(
			this.getClass().getPackage().getName(), "CopilotSettingsAction"));
		openSettingsAction.setEnabled(true);
		tool.addAction(openSettingsAction);
	}

	private void showSettingsDialog() {
		if (toolOptions == null) {
			return;
		}
		CopilotSettingsDialog dialog = new CopilotSettingsDialog(toolOptions, this::applySettings);
		tool.showDialog(dialog);
	}

	private void applySettings() {
		if (provider == null || toolOptions == null) {
			return;
		}
		ChatSettings settings = loadSettings();
		refreshModelRegistry(settings);
		List<ModelEntry> models = ModelRegistry.allModels();
		String defaultModelKey = ModelRegistry.defaultModel().map(ModelEntry::key).orElse(null);
		provider.updateModelCatalog(models, defaultModelKey);
		provider.applyConfiguration(SpringAiChatServiceFactory.create(settings));
	}

	private ChatSettings loadSettings() {
		AiProvider providerValue = AiProvider.fromUserValue(
			toolOptions.getString(OPTION_PROVIDER, CopilotOptions.DEFAULT_PROVIDER));

		ChatSettings.Builder builder = ChatSettings.builder()
				.provider(providerValue)
				.systemPrompt(toolOptions.getString(OPTION_SYSTEM_PROMPT, CopilotOptions.defaultSystemPrompt()))
				.openAiApiKey(toolOptions.getString(OPTION_OPENAI_API_KEY, ""))
				.openAiBaseUrl(toolOptions.getString(OPTION_OPENAI_BASE_URL, ""))
				.openAiModel(toolOptions.getString(OPTION_OPENAI_MODEL, DEFAULT_OPENAI_MODEL))
				.azureApiKey(toolOptions.getString(OPTION_AZURE_API_KEY, ""))
				.azureEndpoint(toolOptions.getString(OPTION_AZURE_ENDPOINT, ""))
				.azureDeployment(toolOptions.getString(OPTION_AZURE_DEPLOYMENT, ""))
				.azureModel(toolOptions.getString(OPTION_AZURE_MODEL, ""))
				.anthropicApiKey(toolOptions.getString(OPTION_ANTHROPIC_API_KEY, ""))
				.anthropicModel(toolOptions.getString(OPTION_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL))
				.ollamaBaseUrl(toolOptions.getString(OPTION_OLLAMA_BASE_URL, DEFAULT_OLLAMA_BASE_URL))
				.ollamaModel(toolOptions.getString(OPTION_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL));

		return builder.build();
	}

	private void refreshModelRegistry(ChatSettings settings) {
		List<ModelEntry> entries = new ArrayList<>();
		addModel(entries, AiProvider.OPENAI, trimToNull(settings.openAiModel()), settings.openAiModel());
		addModel(entries, AiProvider.AZURE_OPENAI, trimToNull(settings.azureDeployment()),
			StringUtils.hasText(settings.azureModel()) ? settings.azureModel().trim() : settings.azureDeployment());
		addModel(entries, AiProvider.ANTHROPIC, trimToNull(settings.anthropicModel()), settings.anthropicModel());
		addModel(entries, AiProvider.OLLAMA, trimToNull(settings.ollamaModel()), settings.ollamaModel());

		ModelRegistry.replaceAll(entries);
		ModelRegistry.setDefaultModelKey(determineDefaultModelKey(settings, entries));
	}

	private static void addModel(List<ModelEntry> entries, AiProvider provider, String identifier, String displayName) {
		if (!StringUtils.hasText(identifier)) {
			return;
		}
		String trimmedId = identifier.trim();
		String friendlyName = StringUtils.hasText(displayName) ? displayName.trim() : trimmedId;
		entries.add(new ModelEntry(provider, trimmedId, friendlyName));
	}

	private static String determineDefaultModelKey(ChatSettings settings, List<ModelEntry> entries) {
		if (settings == null || entries.isEmpty()) {
			return null;
		}
		AiProvider activeProvider = settings.provider() != null ? settings.provider() : AiProvider.OPENAI;
		String identifier = switch (activeProvider) {
			case OPENAI -> trimToNull(settings.openAiModel());
			case AZURE_OPENAI -> trimToNull(settings.azureDeployment());
			case ANTHROPIC -> trimToNull(settings.anthropicModel());
			case OLLAMA -> trimToNull(settings.ollamaModel());
		};
		if (!StringUtils.hasText(identifier)) {
			return entries.stream()
					.filter(entry -> entry.provider() == activeProvider)
					.findFirst()
					.map(ModelEntry::key)
					.orElse(null);
		}
		return ModelRegistry.keyFor(activeProvider, identifier.trim());
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
