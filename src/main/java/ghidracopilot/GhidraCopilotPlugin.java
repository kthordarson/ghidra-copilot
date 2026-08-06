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
import java.util.Properties;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import ghidracopilot.CopilotPluginPackage;
import ghidra.app.context.ListingActionContext;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.DecompilerLocation;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.plugin.core.decompile.DecompilerActionContext;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramSelection;
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
import ghidra.util.Msg;
import ghidra.util.task.TaskMonitorAdapter;
import ghidracopilot.ai.AiProvider;
import ghidracopilot.ai.PermissionManager;
import ghidracopilot.ai.ChatSettings;
import ghidracopilot.ai.SpringAiChatServiceFactory;
import ghidracopilot.ai.tools.CopilotToolRegistry;
import ghidracopilot.config.CopilotConfigPersistence;
import ghidracopilot.model.ModelRegistry;
import ghidracopilot.model.ModelRegistry.ModelEntry;
import ghidracopilot.ui.CopilotProvider;
import ghidracopilot.ui.CopilotSettingsDialog;
import org.springframework.util.StringUtils;

/**
 * Provide class-level documentation that describes what this plugin does.
 */
//@formatter:off
@PluginInfo(
	status = PluginStatus.STABLE,
	packageName = CopilotPluginPackage.NAME,
	category = "AI",
	shortDescription = "Ghidra Copilot Chat",
	description = "Copilot to help with reverse engineering tasks."
)
//@formatter:on
public class GhidraCopilotPlugin extends ProgramPlugin implements OptionsChangeListener {

	private CopilotProvider provider;
	private ToolOptions toolOptions;
	private DockingAction openSettingsAction;
	private DockingAction listingExplainLinesAction;
	private DockingAction listingExplainSelectionAction;
	private DockingAction decompilerExplainLinesAction;
	private DockingAction decompilerExplainSelectionAction;

	private static final int MAX_LISTING_SELECTION_LINES = 160;
	private static final int LISTING_CONTEXT_BEFORE = 6;
	private static final int LISTING_CONTEXT_AFTER = 12;
	private static final int MAX_DECOMPILER_WINDOW_LINES = 160;
	private static final int DECOMPILER_CONTEXT_BEFORE = 6;
	private static final int DECOMPILER_CONTEXT_AFTER = 12;
	private static final int MAX_SNIPPET_CHARS = 8000;

	/**
	 * Plugin constructor.
	 * 
	 * @param tool The plugin tool that this plugin is added to.
	 */
	public GhidraCopilotPlugin(PluginTool tool) {
		super(tool);

		toolOptions = tool.getOptions(OPTIONS_CATEGORY);
		Properties persistedDefaults = CopilotConfigPersistence.load();
		registerOptions(toolOptions, persistedDefaults);
		toolOptions.addOptionsChangeListener(this);
		CopilotConfigPersistence.persistFrom(toolOptions);

	String pluginName = getName();
	provider = new CopilotProvider(this, pluginName);
	provider.addToTool();
	provider.setVisible(true);

	CopilotToolRegistry.configureForPlugin(this);
	CopilotToolRegistry.setPermissionManager(provider.permissionManager());
	provider.wireIntentTool();

	String topicName = this.getClass().getPackage().getName();
	String anchorName = "HelpAnchor";
	provider.setHelpLocation(new HelpLocation(topicName, anchorName));

	createSettingsMenuAction();
	createExplainActions();

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
		removeExplainActions();
		CopilotToolRegistry.clear();
		super.dispose();
	}

	@Override
	public void optionsChanged(ToolOptions options, String optionName, Object oldValue, Object newValue) {
		if (options != toolOptions) {
			return;
		}
		CopilotConfigPersistence.persistFrom(toolOptions);
		applySettings();
	}

	private void registerOptions(ToolOptions options, Properties persistedDefaults) {
		String providerDefault = persistedDefaults.getProperty(OPTION_PROVIDER, CopilotOptions.DEFAULT_PROVIDER);
		String systemPromptDefault =
			persistedDefaults.getProperty(OPTION_SYSTEM_PROMPT, CopilotOptions.defaultSystemPrompt());
		String openAiApiKeyDefault = persistedDefaults.getProperty(OPTION_OPENAI_API_KEY, "");
		String openAiBaseUrlDefault = persistedDefaults.getProperty(OPTION_OPENAI_BASE_URL, "");
		String openAiModelDefault = persistedDefaults.getProperty(OPTION_OPENAI_MODEL, DEFAULT_OPENAI_MODEL);
		String azureApiKeyDefault = persistedDefaults.getProperty(OPTION_AZURE_API_KEY, "");
		String azureEndpointDefault = persistedDefaults.getProperty(OPTION_AZURE_ENDPOINT, "");
		String azureDeploymentDefault = persistedDefaults.getProperty(OPTION_AZURE_DEPLOYMENT, "");
		String azureModelDefault = persistedDefaults.getProperty(OPTION_AZURE_MODEL, "");
		String anthropicApiKeyDefault = persistedDefaults.getProperty(OPTION_ANTHROPIC_API_KEY, "");
		String anthropicModelDefault = persistedDefaults.getProperty(OPTION_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL);
		String ollamaBaseUrlDefault =
			persistedDefaults.getProperty(OPTION_OLLAMA_BASE_URL, DEFAULT_OLLAMA_BASE_URL);
		String ollamaModelDefault = persistedDefaults.getProperty(OPTION_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL);

		options.registerOption(OPTION_PROVIDER, OptionType.STRING_TYPE, providerDefault, null,
			"Large language model provider used for Copilot responses.");
		options.registerOption(OPTION_SYSTEM_PROMPT, OptionType.STRING_TYPE, systemPromptDefault, null,
			"System instruction that is prepended to every request.");

		options.registerOption(OPTION_OPENAI_API_KEY, OptionType.STRING_TYPE, openAiApiKeyDefault, null,
			"Secret key issued by OpenAI.");
		options.registerOption(OPTION_OPENAI_BASE_URL, OptionType.STRING_TYPE, openAiBaseUrlDefault, null,
			"Optional override for the OpenAI API base URL.");
		options.registerOption(OPTION_OPENAI_MODEL, OptionType.STRING_TYPE, openAiModelDefault, null,
			"OpenAI chat model identifier.");

		options.registerOption(OPTION_AZURE_API_KEY, OptionType.STRING_TYPE, azureApiKeyDefault, null,
			"Azure OpenAI API key.");
		options.registerOption(OPTION_AZURE_ENDPOINT, OptionType.STRING_TYPE, azureEndpointDefault, null,
			"Azure OpenAI endpoint URL.");
		options.registerOption(OPTION_AZURE_DEPLOYMENT, OptionType.STRING_TYPE, azureDeploymentDefault, null,
			"Azure OpenAI deployment name.");
		options.registerOption(OPTION_AZURE_MODEL, OptionType.STRING_TYPE, azureModelDefault, null,
			"Optional Azure OpenAI model identifier.");

		options.registerOption(OPTION_ANTHROPIC_API_KEY, OptionType.STRING_TYPE, anthropicApiKeyDefault, null,
			"Anthropic API key.");
		options.registerOption(OPTION_ANTHROPIC_MODEL, OptionType.STRING_TYPE, anthropicModelDefault, null,
			"Anthropic Claude model identifier.");

		options.registerOption(OPTION_OLLAMA_BASE_URL, OptionType.STRING_TYPE, ollamaBaseUrlDefault, null,
			"Ollama server base URL.");
		options.registerOption(OPTION_OLLAMA_MODEL, OptionType.STRING_TYPE, ollamaModelDefault, null,
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

	private void createExplainActions() {
		listingExplainLinesAction = new DockingAction("Copilot: Explain Lines (Listing)", getName()) {
			@Override
			public void actionPerformed(ActionContext context) {
				if (context instanceof ListingActionContext listingContext) {
					handleListingExplain(listingContext, false);
				}
			}

			@Override
			public boolean isAddToPopup(ActionContext context) {
				return context instanceof ListingActionContext listingContext
					&& listingContext.getProgram() != null;
			}
		};
		listingExplainLinesAction.setDescription("Explain the current listing line(s) in the Copilot chat.");
		listingExplainLinesAction.setPopupMenuData(new MenuData(
			new String[] { "Ghidra Copilot", "Explain Lines (Listing)" }, (String) null));
		tool.addAction(listingExplainLinesAction);

		listingExplainSelectionAction =
			new DockingAction("Copilot: Explain Selection (Listing)", getName()) {
				@Override
				public void actionPerformed(ActionContext context) {
					if (context instanceof ListingActionContext listingContext) {
						handleListingExplain(listingContext, true);
					}
				}

				@Override
				public boolean isAddToPopup(ActionContext context) {
					return context instanceof ListingActionContext listingContext
						&& listingContext.getProgram() != null
						&& listingContext.getSelection() != null
						&& !listingContext.getSelection().isEmpty();
				}
			};
		listingExplainSelectionAction.setDescription(
			"Explain the selected listing range in the Copilot chat.");
		listingExplainSelectionAction.setPopupMenuData(new MenuData(
			new String[] { "Ghidra Copilot", "Explain Selection (Listing)" }, (String) null));
		tool.addAction(listingExplainSelectionAction);

		decompilerExplainLinesAction =
			new DockingAction("Copilot: Explain Lines (Decompiler)", getName()) {
				@Override
				public void actionPerformed(ActionContext context) {
					if (context instanceof DecompilerActionContext decompilerContext) {
						handleDecompilerExplain(decompilerContext, false);
					}
				}

				@Override
				public boolean isAddToPopup(ActionContext context) {
					return context instanceof DecompilerActionContext decompilerContext
						&& decompilerContext.getProgram() != null;
				}
			};
		decompilerExplainLinesAction.setDescription(
			"Explain the current decompiler line(s) in the Copilot chat.");
		decompilerExplainLinesAction.setPopupMenuData(new MenuData(
			new String[] { "Ghidra Copilot", "Explain Lines (Decompiler)" }, (String) null));
		tool.addAction(decompilerExplainLinesAction);

		decompilerExplainSelectionAction =
			new DockingAction("Copilot: Explain Selection (Decompiler)", getName()) {
				@Override
				public void actionPerformed(ActionContext context) {
					if (context instanceof DecompilerActionContext decompilerContext) {
						handleDecompilerExplain(decompilerContext, true);
					}
				}

				@Override
				public boolean isAddToPopup(ActionContext context) {
					return context instanceof DecompilerActionContext decompilerContext
						&& decompilerContext.getProgram() != null
						&& hasDecompilerSelection(decompilerContext);
				}
			};
		decompilerExplainSelectionAction.setDescription(
			"Explain the selected decompiler text in the Copilot chat.");
		decompilerExplainSelectionAction.setPopupMenuData(new MenuData(
			new String[] { "Ghidra Copilot", "Explain Selection (Decompiler)" }, (String) null));
		tool.addAction(decompilerExplainSelectionAction);
	}

	private void removeExplainActions() {
		removeAction(listingExplainLinesAction);
		removeAction(listingExplainSelectionAction);
		removeAction(decompilerExplainLinesAction);
		removeAction(decompilerExplainSelectionAction);
	}

	private void removeAction(DockingAction action) {
		if (action != null) {
			tool.removeAction(action);
		}
	}

	private void handleListingExplain(ListingActionContext context, boolean selectionOnly) {
		Program program = context.getProgram();
		if (program == null || provider == null) {
			return;
		}
		ProgramSelection selection = context.getSelection();
		ProgramLocation location = context.getLocation();
		Address pivot = location != null ? location.getAddress() : null;

		AtomicBoolean truncated = new AtomicBoolean(false);
		String snippet = selectionOnly
				? buildListingSelectionSnippet(program, selection, pivot, truncated)
				: buildListingLineSnippet(program, pivot, truncated);
		if (!StringUtils.hasText(snippet)) {
			provider.addSystemMessage("Copilot could not capture listing text to explain.");
			return;
		}

		Function function = findFunction(program, pivot);
		String prompt = buildExplainPrompt(selectionOnly ? "assembly selection" : "assembly lines", snippet, function,
			false, truncated.get());
		String preface = selectionOnly
				? "Explaining the selected listing range with Copilot."
				: "Explaining the current listing lines with Copilot.";
		showProviderAndSend(prompt, preface);
	}

	private void handleDecompilerExplain(DecompilerActionContext context, boolean selectionOnly) {
		Program program = context.getProgram();
		if (program == null || provider == null) {
			return;
		}
		ProgramLocation location = context.getLocation();
		Address pivot = location != null ? location.getAddress() : null;
		AtomicBoolean truncated = new AtomicBoolean(false);

		String selectionPreview = extractDecompilerSelection(context);
		Integer caretLine = context != null ? context.getLineNumber() : null;
		if (caretLine == null && location instanceof DecompilerLocation decompLoc) {
			caretLine = resolveDecompilerLine(decompLoc);
		}

		boolean usedSelection = selectionOnly;
		String snippet;
		if (selectionOnly) {
			snippet = buildDecompilerSelectionSnippet(context, truncated);
		}
		else {
			AtomicBoolean selectionTruncated = new AtomicBoolean(false);
			String selectedText = buildDecompilerSelectionSnippet(context, selectionTruncated);
			if (StringUtils.hasText(selectedText)) {
				snippet = selectedText;
				usedSelection = true;
				truncated.set(selectionTruncated.get());
			}
			else {
				snippet = buildDecompilerLinesSnippet(context, truncated);
			}
		}
		if (!StringUtils.hasText(snippet)) {
			provider.addSystemMessage("Copilot could not capture decompiler text to explain.");
			logDecompilerCapture(selectionOnly, usedSelection, selectionPreview, caretLine, pivot, null, null);
			return;
		}

		Function function = findFunction(program, pivot);
		String prompt = buildExplainPrompt(
			usedSelection ? "decompiler selection" : "decompiler lines", snippet, function, true, truncated.get());
		String preface = usedSelection
			? "Explaining the selected decompiler text with Copilot."
			: "Explaining the current decompiler lines with Copilot.";
		logDecompilerCapture(selectionOnly, usedSelection, selectionPreview, caretLine, pivot, snippet, function);
		showProviderAndSend(prompt, preface);
	}

	private void logDecompilerCapture(boolean selectionRequested, boolean usedSelection, String rawSelection,
			Integer caretLine, Address pivot, String snippet, Function function) {
		try {
			StringBuilder sb = new StringBuilder("Decompiler explain capture: ");
			sb.append("selectionRequested=").append(selectionRequested)
				.append(", usedSelection=").append(usedSelection)
				.append(", caretLine=").append(caretLine)
				.append(", pivot=").append(pivot)
				.append(", function=").append(function != null ? function.getName() : "null");
			if (rawSelection != null) {
				sb.append(", rawSelectionLen=").append(rawSelection.length());
			}
			if (snippet != null) {
				int previewLen = Math.min(snippet.length(), 512);
				sb.append(", snippetLen=").append(snippet.length())
					.append(", snippetPreview=\"").append(snippet.substring(0, previewLen).replaceAll("\\R", "\\\\n"))
					.append(snippet.length() > previewLen ? "...\" (truncated)" : "\"");
			}
			Msg.debug(getClass(), sb.toString());
		}
		catch (Exception ignored) {
			// logging best-effort
		}
	}

	private void showProviderAndSend(String prompt, String preface) {
		if (provider == null || !StringUtils.hasText(prompt)) {
			return;
		}
		tool.showComponentProvider(provider, true);
		provider.toFront();
		provider.sendPrompt(prompt, preface);
	}

	private String buildListingSelectionSnippet(Program program, ProgramSelection selection, Address pivot,
			AtomicBoolean truncated) {
		if (program == null || selection == null || selection.isEmpty()) {
			return "";
		}
		Listing listing = program.getListing();
		StringBuilder sb = new StringBuilder();
		int count = 0;
		boolean hasMore = false;

		var instructions = listing.getInstructions(selection, true);
		if (instructions.hasNext()) {
			while (instructions.hasNext()) {
				Instruction instruction = instructions.next();
				String prefix = pivot != null && instruction.getMinAddress().equals(pivot) ? "> " : "* ";
				appendInstructionLine(sb, prefix, instruction);
				count++;
				if (count >= MAX_LISTING_SELECTION_LINES) {
					hasMore = instructions.hasNext();
					break;
				}
			}
		}
		else {
			var units = listing.getCodeUnits(selection, true);
			while (units.hasNext()) {
				CodeUnit unit = units.next();
				String prefix = pivot != null && unit.getMinAddress().equals(pivot) ? "> " : "* ";
				appendCodeUnitLine(sb, prefix, unit);
				count++;
				if (count >= MAX_LISTING_SELECTION_LINES) {
					hasMore = units.hasNext();
					break;
				}
			}
		}

		if (hasMore) {
			truncated.set(true);
			sb.append("... (selection preview truncated)\n");
		}
		return capSnippet(sb.toString().stripTrailing(), truncated);
	}

	private String buildListingLineSnippet(Program program, Address address, AtomicBoolean truncated) {
		if (program == null || address == null) {
			return "";
		}
		Listing listing = program.getListing();
		Instruction center = listing.getInstructionAt(address);
		if (center == null) {
			center = listing.getInstructionContaining(address);
		}
		if (center == null) {
			CodeUnit codeUnit = listing.getCodeUnitAt(address);
			return codeUnit != null ? codeUnit.getMinAddress() + ": " + codeUnit : "";
		}

		List<Instruction> before = new ArrayList<>();
		Instruction cursor = center;
		for (int i = 0; i < LISTING_CONTEXT_BEFORE; i++) {
			Instruction prev = listing.getInstructionBefore(cursor.getMinAddress());
			if (prev == null) {
				break;
			}
			before.add(0, prev);
			cursor = prev;
		}

		List<Instruction> after = new ArrayList<>();
		cursor = center;
		for (int i = 0; i < LISTING_CONTEXT_AFTER; i++) {
			Instruction next = listing.getInstructionAfter(cursor.getMinAddress());
			if (next == null) {
				break;
			}
			after.add(next);
			cursor = next;
		}

		boolean hadMoreBefore = listing.getInstructionBefore(center.getMinAddress()) != null
			&& before.size() == LISTING_CONTEXT_BEFORE;
		boolean hadMoreAfter = listing.getInstructionAfter(cursor.getMinAddress()) != null
			&& after.size() == LISTING_CONTEXT_AFTER;
		if (hadMoreBefore || hadMoreAfter) {
			truncated.set(true);
		}

		StringBuilder sb = new StringBuilder();
		for (Instruction instruction : before) {
			appendInstructionLine(sb, "  ", instruction);
		}
		appendInstructionLine(sb, "> ", center);
		for (Instruction instruction : after) {
			appendInstructionLine(sb, "  ", instruction);
		}
		return capSnippet(sb.toString().stripTrailing(), truncated);
	}

	private void appendInstructionLine(StringBuilder sb, String prefix, Instruction instruction) {
		sb.append(prefix)
			.append(instruction.getMinAddress())
			.append(": ")
			.append(instruction)
			.append(System.lineSeparator());
	}

	private void appendCodeUnitLine(StringBuilder sb, String prefix, CodeUnit unit) {
		sb.append(prefix)
			.append(unit.getMinAddress())
			.append(": ")
			.append(unit)
			.append(System.lineSeparator());
	}

	private String buildDecompilerSelectionSnippet(DecompilerActionContext context, AtomicBoolean truncated) {
		String selected = extractDecompilerSelection(context);
		if (!StringUtils.hasText(selected)) {
			return "";
		}
		return capSnippet(selected, truncated);
	}

	private String buildDecompilerLinesSnippet(DecompilerActionContext context, AtomicBoolean truncated) {
		String decompiled = getDecompiledText(context);
		if (!StringUtils.hasText(decompiled)) {
			return "";
		}
		List<String> lines = splitLines(decompiled);
		int targetLine = context != null ? context.getLineNumber() : -1;
		if (targetLine < 0) {
			ProgramLocation location = context != null ? context.getLocation() : null;
			if (location instanceof DecompilerLocation decompLoc) {
				targetLine = resolveDecompilerLine(decompLoc);
			}
		}
		if (targetLine < 0 || targetLine >= lines.size()) {
			return "";
		}
		String lineText = lines.get(targetLine);
		return capSnippet(lineText, truncated);
	}

	private String getDecompiledText(DecompilerActionContext context) {
		if (context == null) {
			return "";
		}
		ProgramLocation location = context.getLocation();
		if (location instanceof DecompilerLocation decompilerLocation) {
			try {
				DecompileResults results = decompilerLocation.getDecompile();
				if (results != null && results.decompileCompleted()
					&& results.getDecompiledFunction() != null) {
					String c = results.getDecompiledFunction().getC();
					if (StringUtils.hasText(c)) {
						return c;
					}
				}
			}
			catch (Exception ex) {
				Msg.debug(getClass(), "Unable to use cached decompile results", ex);
			}
		}
		return decompileWithInterface(context.getFunction());
	}

	private String decompileWithInterface(Function function) {
		if (function == null) {
			return "";
		}
		DecompInterface iface = new DecompInterface();
		try {
			if (!iface.openProgram(function.getProgram())) {
				return "";
			}
			DecompileResults results =
				iface.decompileFunction(function, 30, TaskMonitorAdapter.DUMMY_MONITOR);
			if (results != null && results.decompileCompleted()
				&& results.getDecompiledFunction() != null) {
				return results.getDecompiledFunction().getC();
			}
			return "";
		}
		finally {
			iface.dispose();
		}
	}

	private int resolveDecompilerLine(DecompilerLocation location) {
		if (location == null) {
			return -1;
		}
		Integer line = invokeInt(location, "getLineNumber");
		return line != null ? line : -1;
	}

	private boolean hasDecompilerSelection(DecompilerActionContext context) {
		return StringUtils.hasText(extractDecompilerSelection(context));
	}

	private String extractDecompilerSelection(DecompilerActionContext context) {
		if (context == null) {
			return "";
		}
		try {
			java.util.concurrent.atomic.AtomicReference<String> ref = new java.util.concurrent.atomic.AtomicReference<>("");
			java.util.concurrent.atomic.AtomicBoolean hadError = new java.util.concurrent.atomic.AtomicBoolean(false);
			java.util.concurrent.atomic.AtomicReference<String> selectedTextRef = new java.util.concurrent.atomic.AtomicReference<>("");
			java.util.concurrent.atomic.AtomicReference<String> highlightedTextRef = new java.util.concurrent.atomic.AtomicReference<>("");
			java.util.concurrent.atomic.AtomicReference<String> providerSelectionRef = new java.util.concurrent.atomic.AtomicReference<>("");
			java.util.concurrent.atomic.AtomicReference<String> fieldSelectionRef = new java.util.concurrent.atomic.AtomicReference<>("");
			java.util.concurrent.atomic.AtomicReference<String> clipboardSelectionRef = new java.util.concurrent.atomic.AtomicReference<>("");
			java.util.concurrent.atomic.AtomicReference<String> usedSourceRef = new java.util.concurrent.atomic.AtomicReference<>("none");
			Runnable fetch = () -> {
				try {
					Object panel = context.getDecompilerPanel();
					// Prefer the clipboard provider's cached selection (mirrors Copy behavior).
					try {
						Object clipboard = invoke(panel, "getClipboard"); // may be null
						if (clipboard == null) {
							java.lang.reflect.Field f = panel.getClass().getDeclaredField("clipboard");
							f.setAccessible(true);
							clipboard = f.get(panel);
						}
						if (clipboard != null) {
							java.lang.reflect.Field selField = clipboard.getClass().getDeclaredField("selection");
							selField.setAccessible(true);
							Object selection = selField.get(clipboard);
							if (selection instanceof docking.widgets.fieldpanel.support.FieldSelection fs && !fs.isEmpty()) {
								// Try to mirror the clipboard's own formatting if possible.
								try {
									java.lang.reflect.Method getText = clipboard.getClass().getDeclaredMethod("getText");
									getText.setAccessible(true);
									Object text = getText.invoke(clipboard);
									if (text instanceof String s && StringUtils.hasText(s)) {
										ref.set(s);
										clipboardSelectionRef.set(s);
										usedSourceRef.set("clipboard:getText");
										return;
									}
								}
								catch (Exception ignored) {
									// fall through to helper-based formatting
								}
								Object fieldPanelObj = invoke(panel, "getFieldPanel");
								if (fieldPanelObj instanceof docking.widgets.fieldpanel.FieldPanel fp) {
									String selectionText = docking.widgets.fieldpanel.support.FieldSelectionHelper
										.getAllSelectedText(fs, fp);
									if (StringUtils.hasText(selectionText)) {
										ref.set(selectionText);
										clipboardSelectionRef.set(selectionText);
										usedSourceRef.set("clipboard:helper");
										return;
									}
								}
							}
						}
					}
					catch (Exception ignored) {
						// best-effort
					}

					String selected = invokeString(panel, "getSelectedText");
					if (StringUtils.hasText(selected)) {
						ref.set(selected);
						selectedTextRef.set(selected);
						usedSourceRef.set("panelSelectedText");
						return;
					}
					String highlighted = invokeString(panel, "getHighlightedText");
					if (StringUtils.hasText(highlighted)) {
						ref.set(highlighted);
						highlightedTextRef.set(highlighted);
						usedSourceRef.set("highlightedText");
						return;
					}
					Object provider = context.getComponentProvider();
					String textSelection = invokeString(provider, "getTextSelection");
					if (StringUtils.hasText(textSelection)) {
						ref.set(textSelection);
						providerSelectionRef.set(textSelection);
						usedSourceRef.set("providerTextSelection");
						return;
					}
					// Try raw field selection text via FieldSelectionHelper.
					try {
						docking.widgets.fieldpanel.FieldPanel fieldPanel =
							(docking.widgets.fieldpanel.FieldPanel) invoke(panel, "getFieldPanel");
						if (fieldPanel != null) {
							docking.widgets.fieldpanel.support.FieldSelection selection = fieldPanel.getSelection();
							if (selection != null && !selection.isEmpty()) {
								String selectionText = docking.widgets.fieldpanel.support.FieldSelectionHelper
									.getAllSelectedText(selection, fieldPanel);
								if (StringUtils.hasText(selectionText)) {
									ref.set(selectionText);
									fieldSelectionRef.set(selectionText);
									usedSourceRef.set("fieldSelection");
									return;
								}
							}
						}
					}
					catch (Exception ignored) {
						// best-effort
					}
				}
				catch (Exception ex) {
					Msg.debug(getClass(), "Failed to fetch decompiler selection", ex);
					hadError.set(true);
				}
			};
			if (javax.swing.SwingUtilities.isEventDispatchThread()) {
				fetch.run();
			}
			else {
				javax.swing.SwingUtilities.invokeAndWait(fetch);
			}
			if (!StringUtils.hasText(ref.get())) {
				Msg.warn(getClass(),
					"Decompiler selection unavailable; lengths selected/highlight/provider/field/clipboard = "
						+ selectedTextRef.get().length()
						+ "/"
						+ highlightedTextRef.get().length()
						+ "/"
						+ providerSelectionRef.get().length()
						+ "/"
						+ fieldSelectionRef.get().length()
						+ "/"
						+ clipboardSelectionRef.get().length());
			}
			else {
				Msg.warn(getClass(),
					"Decompiler selection source=" + usedSourceRef.get() + " len=" + ref.get().length());
			}
			if (hadError.get()) {
				Msg.warn(getClass(), "Decompiler selection fetch encountered errors; selection may be empty");
			}
			return ref.get();
		}
		catch (Exception ex) {
			Msg.debug(getClass(), "Failed to fetch decompiler selection (EDT)", ex);
			return "";
		}
	}

	private String capSnippet(String text, AtomicBoolean truncated) {
		if (text == null) {
			return "";
		}
		if (text.length() > MAX_SNIPPET_CHARS) {
			if (truncated != null) {
				truncated.set(true);
			}
			return text.substring(0, MAX_SNIPPET_CHARS) + "\n... (truncated)";
		}
		return text;
	}

	private String buildExplainPrompt(String scope, String code, Function function, boolean isC, boolean truncated) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("Explain the following ").append(scope).append('.');
		if (function != null) {
			prompt.append(" Function: ").append(function.getName())
				.append(" @ ").append(function.getEntryPoint()).append('.');
		}
		if (truncated) {
			prompt.append(" The snippet was truncated for brevity; call out if more context would change the answer.");
		}
		prompt.append("\n- Summarize behavior in 3-6 bullets for a reverse engineer.\n");
		prompt.append("\n- Suggest a better name for the function and parameters.\n");
		prompt.append("\n- Suggest better names and data types for variables.\n");
		prompt.append("- Highlight side effects, noteworthy calls, and implicit assumptions.\n");
		prompt.append("```").append(isC ? "c" : "asm").append('\n');
		prompt.append(code).append('\n').append("```");
		return prompt.toString();
	}

	private Function findFunction(Program program, Address address) {
		if (program == null || address == null) {
			return null;
		}
		try {
			FunctionManager functionManager = program.getFunctionManager();
			return functionManager != null ? functionManager.getFunctionContaining(address) : null;
		}
		catch (Exception ex) {
			Msg.debug(getClass(), "Failed to resolve function for address " + address, ex);
			return null;
		}
	}

	private List<String> splitLines(String c) {
		if (c == null || c.isBlank()) {
			return List.of();
		}
		String[] parts = c.split("\\R", -1);
		List<String> lines = new ArrayList<>(parts.length);
		java.util.Collections.addAll(lines, parts);
		while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private Integer invokeInt(Object target, String method) {
		try {
			Object value = invoke(target, method);
			return value instanceof Number number ? number.intValue() : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String invokeString(Object target, String method) {
		try {
			Object value = invoke(target, method);
			return value instanceof String s ? s : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private Object invoke(Object target, String method) throws Exception {
		if (target == null || method == null) {
			return null;
		}
		Method m = findZeroArgMethod(target.getClass(), method);
		if (m == null) {
			return null;
		}
		m.setAccessible(true);
		return m.invoke(target);
	}

	private Method findZeroArgMethod(Class<?> type, String name) {
		for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
			try {
				return cursor.getDeclaredMethod(name);
			}
			catch (NoSuchMethodException ignored) {
				// continue searching
			}
		}
		return null;
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
		applyModelCatalog();
		provider.applyConfiguration(SpringAiChatServiceFactory.create(settings));
		if (settings.provider() == AiProvider.GITHUB_COPILOT) {
			refreshCopilotModelsAsync();
		}
	}

	private void applyModelCatalog() {
		List<ModelEntry> models = ModelRegistry.allModels();
		String defaultModelKey = ModelRegistry.defaultModel().map(ModelEntry::key).orElse(null);
		provider.updateModelCatalog(models, defaultModelKey);
	}

	/**
	 * Fetches the full GitHub Copilot model catalog (a {@code gh auth token}
	 * subprocess call plus an HTTPS request) on a background thread, then
	 * applies the result on the EDT. Must never run synchronously on the EDT.
	 */
	private void refreshCopilotModelsAsync() {
		new Thread(() -> {
			List<ModelEntry> fetched = new ArrayList<>();
			try {
				var tokenProvider = new ghidracopilot.ai.CopilotTokenProvider();
				for (var model : tokenProvider.fetchAvailableModels()) {
					fetched.add(new ModelEntry(AiProvider.GITHUB_COPILOT, model.id(), model.displayLabel()));
				}
			}
			catch (Exception ex) {
				Msg.warn(this, "Failed to fetch Copilot models: " + ex.getMessage());
				return;
			}
			SwingUtilities.invokeLater(() -> applyFetchedCopilotModels(fetched));
		}, "GhidraCopilot-CopilotModelFetch").start();
	}

	private void applyFetchedCopilotModels(List<ModelEntry> fetched) {
		if (provider == null || toolOptions == null || fetched.isEmpty()) {
			return;
		}
		ChatSettings settings = loadSettings();
		if (settings.provider() != AiProvider.GITHUB_COPILOT) {
			// Provider was switched away from Copilot while the fetch was in flight.
			return;
		}
		String configured = trimToNull(settings.copilotModel());
		if (configured != null && fetched.stream().noneMatch(e -> e.identifier().equals(configured))) {
			fetched.add(0, new ModelEntry(AiProvider.GITHUB_COPILOT, configured, configured));
		}
		ModelRegistry.replaceAll(fetched);
		ModelRegistry.setDefaultModelKey(determineDefaultModelKey(settings, fetched));
		applyModelCatalog();
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
				.ollamaModel(toolOptions.getString(OPTION_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL))
				.copilotModel(toolOptions.getString(CopilotOptions.OPTION_COPILOT_MODEL, CopilotOptions.DEFAULT_COPILOT_MODEL));

		return builder.build();
	}

	private void refreshModelRegistry(ChatSettings settings) {
		List<ModelEntry> entries = new ArrayList<>();
		AiProvider activeProvider = settings.provider() != null ? settings.provider() : AiProvider.OPENAI;

		switch (activeProvider) {
			case OPENAI ->
				addModel(entries, AiProvider.OPENAI, trimToNull(settings.openAiModel()), settings.openAiModel());
			case AZURE_OPENAI ->
				addModel(entries, AiProvider.AZURE_OPENAI, trimToNull(settings.azureDeployment()),
					StringUtils.hasText(settings.azureModel()) ? settings.azureModel().trim() : settings.azureDeployment());
			case ANTHROPIC ->
				addModel(entries, AiProvider.ANTHROPIC, trimToNull(settings.anthropicModel()), settings.anthropicModel());
			case OLLAMA ->
				addModel(entries, AiProvider.OLLAMA, trimToNull(settings.ollamaModel()), settings.ollamaModel());
			case GITHUB_COPILOT -> {
				// The full catalog is fetched asynchronously in refreshCopilotModelsAsync():
				// fetching it here would run `gh auth token` + an HTTPS call synchronously
				// on the EDT (this method is called from optionsChanged and from an
				// invokeLater at startup) and could freeze Ghidra's UI for the duration.
				String configured = trimToNull(settings.copilotModel());
				if (configured != null) {
					entries.add(new ModelEntry(AiProvider.GITHUB_COPILOT, configured, configured));
				}
			}
		}

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
			case GITHUB_COPILOT -> trimToNull(settings.copilotModel());
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
