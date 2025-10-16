package ghidracopilot.ui;

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

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

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
