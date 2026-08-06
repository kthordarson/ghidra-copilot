package ghidracopilot.ui;

import static ghidracopilot.CopilotOptions.DEFAULT_ANTHROPIC_MODEL;
import static ghidracopilot.CopilotOptions.DEFAULT_COPILOT_MODEL;
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
import static ghidracopilot.CopilotOptions.OPTION_COPILOT_MODEL;
import static ghidracopilot.CopilotOptions.OPTION_PROVIDER;
import static ghidracopilot.CopilotOptions.OPTION_SYSTEM_PROMPT;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import docking.DialogComponentProvider;
import ghidra.framework.options.ToolOptions;
import ghidra.util.HelpLocation;
import ghidracopilot.CopilotOptions;
import ghidracopilot.ai.AiProvider;

public class CopilotSettingsDialog extends DialogComponentProvider {

	private final ToolOptions toolOptions;
	private final Runnable onCloseCallback;

	private JComboBox<AiProvider> providerCombo;
	private JTextArea systemPromptArea;
	private JPanel providerCards;
	private CardLayout providerCardLayout;
	private JButton testConnectionButton;
	private JLabel testResultLabel;
	private final Map<AiProvider, ProviderPanel> providerPanels = new EnumMap<>(AiProvider.class);

	public CopilotSettingsDialog(ToolOptions toolOptions, Runnable onCloseCallback) {
		super("Ghidra Copilot Settings");
		this.toolOptions = toolOptions;
		this.onCloseCallback = onCloseCallback != null ? onCloseCallback : () -> {};

		buildUI();
		loadValues();

		addOKButton();
		addCancelButton();
		setRememberSize(false);
		setHelpLocation(new HelpLocation("ghidracopilot", "CopilotSettingsDialog"));
	}

	private void buildUI() {
		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(buildGeneralSettingsPanel(), BorderLayout.NORTH);
		content.add(buildProviderCards(), BorderLayout.CENTER);

		JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		testPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
		testConnectionButton = new JButton("Test Connection");
		testConnectionButton.addActionListener(e -> testConnection());
		testResultLabel = new JLabel("");
		testPanel.add(testConnectionButton);
		testPanel.add(testResultLabel);
		content.add(testPanel, BorderLayout.SOUTH);

		addWorkPanel(content);
	}

	private JPanel buildGeneralSettingsPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.insets = new Insets(0, 0, 5, 5);
		gbc.anchor = GridBagConstraints.WEST;

		JLabel providerLabel = new JLabel("Provider");
		panel.add(providerLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		providerCombo = new JComboBox<>(AiProvider.values());
		providerCombo.setRenderer(new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof AiProvider provider) {
					setText(provider.displayName());
				}
				return this;
			}
		});
		providerCombo.addActionListener(e -> showProviderCard(getSelectedProvider()));
		panel.add(providerCombo, gbc);

		gbc.gridx = 0;
		gbc.gridy++;
		gbc.gridwidth = 2;
		gbc.insets = new Insets(5, 0, 5, 0);
		gbc.fill = GridBagConstraints.BOTH;
		gbc.weightx = 1.0;
		gbc.weighty = 1.0;

		systemPromptArea = new JTextArea();
		systemPromptArea.setLineWrap(true);
		systemPromptArea.setWrapStyleWord(true);
		systemPromptArea.setMargin(new Insets(5, 5, 5, 5));
		systemPromptArea.setRows(6);

		JScrollPane scrollPane = new JScrollPane(systemPromptArea);
		scrollPane.setBorder(BorderFactory.createTitledBorder("System Prompt"));
		panel.add(scrollPane, gbc);

		return panel;
	}

	private JPanel buildProviderCards() {
		providerCardLayout = new CardLayout();
		providerCards = new JPanel(providerCardLayout);
		providerCards.setBorder(BorderFactory.createTitledBorder("Provider Configuration"));

		addProviderPanel(AiProvider.OPENAI, createOpenAiPanel());
		addProviderPanel(AiProvider.AZURE_OPENAI, createAzurePanel());
		addProviderPanel(AiProvider.ANTHROPIC, createAnthropicPanel());
		addProviderPanel(AiProvider.OLLAMA, createOllamaPanel());
		addProviderPanel(AiProvider.GITHUB_COPILOT, createCopilotPanel());

		return providerCards;
	}

	private void addProviderPanel(AiProvider provider, ProviderPanel panel) {
		providerPanels.put(provider, panel);
		providerCards.add(panel, provider.name());
	}

	private ProviderPanel createOpenAiPanel() {
		JPasswordField apiKeyField = new JPasswordField();
		JTextField baseUrlField = new JTextField();
		JTextField modelField = new JTextField();
		apiKeyField.setColumns(30);
		baseUrlField.setColumns(30);
		modelField.setColumns(30);

		return new ProviderPanel("OpenAI Settings") {
			{
				addRow("API Key", apiKeyField);
				addRow("Base URL", baseUrlField);
				addRow("Model", modelField);
			}

			@Override
			void load(ToolOptions options) {
				apiKeyField.setText(options.getString(OPTION_OPENAI_API_KEY, ""));
				baseUrlField.setText(options.getString(OPTION_OPENAI_BASE_URL, ""));
				modelField.setText(options.getString(OPTION_OPENAI_MODEL, DEFAULT_OPENAI_MODEL));
			}

			@Override
			void persist(ToolOptions options) {
				options.setString(OPTION_OPENAI_API_KEY, new String(apiKeyField.getPassword()).trim());
				options.setString(OPTION_OPENAI_BASE_URL, baseUrlField.getText().trim());
				options.setString(OPTION_OPENAI_MODEL, modelField.getText().trim());
			}
		};
	}

	private ProviderPanel createAzurePanel() {
		JPasswordField apiKeyField = new JPasswordField();
		JTextField endpointField = new JTextField();
		JTextField deploymentField = new JTextField();
		JTextField modelField = new JTextField();
		apiKeyField.setColumns(30);
		endpointField.setColumns(30);
		deploymentField.setColumns(30);
		modelField.setColumns(30);

		return new ProviderPanel("Azure OpenAI Settings") {
			{
				addRow("API Key", apiKeyField);
				addRow("Endpoint", endpointField);
				addRow("Deployment", deploymentField);
				addRow("Model (optional)", modelField);
			}

			@Override
			void load(ToolOptions options) {
				apiKeyField.setText(options.getString(OPTION_AZURE_API_KEY, ""));
				endpointField.setText(options.getString(OPTION_AZURE_ENDPOINT, ""));
				deploymentField.setText(options.getString(OPTION_AZURE_DEPLOYMENT, ""));
				modelField.setText(options.getString(OPTION_AZURE_MODEL, ""));
			}

			@Override
			void persist(ToolOptions options) {
				options.setString(OPTION_AZURE_API_KEY, new String(apiKeyField.getPassword()).trim());
				options.setString(OPTION_AZURE_ENDPOINT, endpointField.getText().trim());
				options.setString(OPTION_AZURE_DEPLOYMENT, deploymentField.getText().trim());
				options.setString(OPTION_AZURE_MODEL, modelField.getText().trim());
			}
		};
	}

	private ProviderPanel createAnthropicPanel() {
		JPasswordField apiKeyField = new JPasswordField();
		JTextField modelField = new JTextField();
		apiKeyField.setColumns(30);
		modelField.setColumns(30);

		return new ProviderPanel("Anthropic Settings") {
			{
				addRow("API Key", apiKeyField);
				addRow("Model", modelField);
			}

			@Override
			void load(ToolOptions options) {
				apiKeyField.setText(options.getString(OPTION_ANTHROPIC_API_KEY, ""));
				modelField.setText(options.getString(OPTION_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL));
			}

			@Override
			void persist(ToolOptions options) {
				options.setString(OPTION_ANTHROPIC_API_KEY, new String(apiKeyField.getPassword()).trim());
				options.setString(OPTION_ANTHROPIC_MODEL, modelField.getText().trim());
			}
		};
	}

	private ProviderPanel createOllamaPanel() {
		JTextField baseUrlField = new JTextField();
		JTextField modelField = new JTextField();
		baseUrlField.setColumns(30);
		modelField.setColumns(30);

		return new ProviderPanel("Ollama Settings") {
			{
				addRow("Base URL", baseUrlField);
				addRow("Model", modelField);
			}

			@Override
			void load(ToolOptions options) {
				baseUrlField.setText(options.getString(OPTION_OLLAMA_BASE_URL, DEFAULT_OLLAMA_BASE_URL));
				modelField.setText(options.getString(OPTION_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL));
			}

			@Override
			void persist(ToolOptions options) {
				options.setString(OPTION_OLLAMA_BASE_URL, baseUrlField.getText().trim());
				options.setString(OPTION_OLLAMA_MODEL, modelField.getText().trim());
			}
		};
	}

	private ProviderPanel createCopilotPanel() {
		JComboBox<String> modelCombo = new JComboBox<>();
		modelCombo.setEditable(true);
		modelCombo.setPrototypeDisplayValue("claude-sonnet-4.6-thinking-xxxxx");
		JLabel infoLabel = new JLabel(
			"<html>Uses your GitHub CLI login (<code>gh auth token</code>).<br>" +
			"No API key needed — just run <code>gh auth login</code> first.</html>");
		JButton refreshButton = new JButton("Refresh Models");

		return new ProviderPanel("GitHub Copilot Settings") {
			{
				JPanel modelRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
				modelRow.setOpaque(false);
				modelRow.add(modelCombo);
				modelRow.add(refreshButton);
				addRow("Model", modelRow);
				addRow("", infoLabel);

				refreshButton.addActionListener(e -> loadModels(modelCombo, infoLabel));
			}

			@Override
			void load(ToolOptions options) {
				String saved = options.getString(OPTION_COPILOT_MODEL, DEFAULT_COPILOT_MODEL);
				modelCombo.removeAllItems();
				modelCombo.addItem(saved);
				modelCombo.setSelectedItem(saved);
				loadModels(modelCombo, infoLabel);
			}

			@Override
			void persist(ToolOptions options) {
				Object selected = modelCombo.getSelectedItem();
				String model = selected != null ? selected.toString().trim() : DEFAULT_COPILOT_MODEL;
				options.setString(OPTION_COPILOT_MODEL, model);
			}
		};
	}

	private void loadModels(JComboBox<String> combo, JLabel statusLabel) {
		new Thread(() -> {
			try {
				var tokenProvider = new ghidracopilot.ai.CopilotTokenProvider();
				var models = tokenProvider.fetchAvailableModels();
				SwingUtilities.invokeLater(() -> {
					Object current = combo.getSelectedItem();
					combo.removeAllItems();
					for (var m : models) {
						combo.addItem(m.id());
					}
					if (current != null && !current.toString().isBlank()) {
						combo.setSelectedItem(current);
					}
				});
			}
			catch (Exception ex) {
				SwingUtilities.invokeLater(() ->
					statusLabel.setText("<html><font color='red'>Failed to load models: " +
						ex.getMessage() + "</font></html>"));
			}
		}, "CopilotModelFetch").start();
	}

	private void loadValues() {
		AiProvider provider = AiProvider.fromUserValue(
			toolOptions.getString(OPTION_PROVIDER, CopilotOptions.DEFAULT_PROVIDER));
		providerCombo.setSelectedItem(provider);
		systemPromptArea.setText(toolOptions.getString(OPTION_SYSTEM_PROMPT, CopilotOptions.defaultSystemPrompt()));
		systemPromptArea.setCaretPosition(0);

		for (ProviderPanel panel : providerPanels.values()) {
			panel.load(toolOptions);
		}

		SwingUtilities.invokeLater(() -> showProviderCard(provider));

	}

	private AiProvider getSelectedProvider() {
		AiProvider provider = (AiProvider) providerCombo.getSelectedItem();
		return provider != null ? provider : AiProvider.OPENAI;
	}

	private void showProviderCard(AiProvider provider) {
		if (provider == null) {
			return;
		}
		providerCardLayout.show(providerCards, provider.name());
	}

	@Override
	protected void okCallback() {
		AiProvider provider = getSelectedProvider();
		toolOptions.setString(OPTION_PROVIDER, provider.id());
		toolOptions.setString(OPTION_SYSTEM_PROMPT, systemPromptArea.getText());

		for (ProviderPanel panel : providerPanels.values()) {
			panel.persist(toolOptions);
		}

		close();
		onCloseCallback.run();
	}

	private void testConnection() {
		// Persist current form values into options so we can build settings from them
		AiProvider provider = getSelectedProvider();
		toolOptions.setString(OPTION_PROVIDER, provider.id());
		toolOptions.setString(OPTION_SYSTEM_PROMPT, systemPromptArea.getText());
		for (ProviderPanel panel : providerPanels.values()) {
			panel.persist(toolOptions);
		}

		testConnectionButton.setEnabled(false);
		testResultLabel.setText("Testing...");
		testResultLabel.setForeground(CopilotTheme.thinkingText());

		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				try {
					ghidracopilot.ai.ChatSettings settings = buildSettingsFromOptions();
					ghidracopilot.ai.SpringAiChatServiceFactory.Result result =
						ghidracopilot.ai.SpringAiChatServiceFactory.create(settings);
					if (result.isSuccess()) {
						return null;
					}
					return result.errorMessage();
				}
				catch (Exception ex) {
					return ex.getMessage() != null ? ex.getMessage() : "Connection failed";
				}
			}

			@Override
			protected void done() {
				testConnectionButton.setEnabled(true);
				try {
					String error = get();
					if (error == null) {
						testResultLabel.setText("✓ Connected successfully");
						testResultLabel.setForeground(CopilotTheme.stateCompleted());
					}
					else {
						testResultLabel.setText("✗ " + error);
						testResultLabel.setForeground(CopilotTheme.stateFailed());
					}
				}
				catch (Exception ex) {
					testResultLabel.setText("✗ " + ex.getMessage());
					testResultLabel.setForeground(CopilotTheme.stateFailed());
				}
			}
		}.execute();
	}

	private ghidracopilot.ai.ChatSettings buildSettingsFromOptions() {
		AiProvider provider = AiProvider.fromUserValue(
			toolOptions.getString(OPTION_PROVIDER, CopilotOptions.DEFAULT_PROVIDER));
		return ghidracopilot.ai.ChatSettings.builder()
			.provider(provider)
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
			.copilotModel(toolOptions.getString(OPTION_COPILOT_MODEL, DEFAULT_COPILOT_MODEL))
			.build();
	}

	@Override
	protected void cancelCallback() {
		close();
	}

	private abstract static class ProviderPanel extends JPanel {

		private int row = 1;

		ProviderPanel(String title) {
			super(new GridBagLayout());
			setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
			JLabel heading = new JLabel(title);
			GridBagConstraints headingConstraints = new GridBagConstraints();
			headingConstraints.gridx = 0;
			headingConstraints.gridy = 0;
			headingConstraints.gridwidth = 2;
			headingConstraints.anchor = GridBagConstraints.WEST;
			headingConstraints.insets = new Insets(0, 0, 10, 0);
			add(heading, headingConstraints);
		}

		void addRow(String labelText, JComponent input) {
			GridBagConstraints labelConstraints = new GridBagConstraints();
			labelConstraints.gridy = row;
			labelConstraints.gridx = 0;
			labelConstraints.anchor = GridBagConstraints.WEST;
			labelConstraints.insets = new Insets(0, 0, 8, 10);

			GridBagConstraints fieldConstraints = new GridBagConstraints();
			fieldConstraints.gridy = row;
			fieldConstraints.gridx = 1;
			fieldConstraints.weightx = 1.0;
			fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
			fieldConstraints.insets = new Insets(0, 0, 8, 0);

			JLabel label = new JLabel(labelText);
			add(label, labelConstraints);
			add(input, fieldConstraints);
			row++;
		}

		abstract void load(ToolOptions options);

		abstract void persist(ToolOptions options);
	}
}
