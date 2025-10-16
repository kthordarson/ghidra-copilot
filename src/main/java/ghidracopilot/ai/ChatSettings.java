package ghidracopilot.ai;

/**
 * Strongly typed configuration for wiring Spring AI chat clients.
 */
public final class ChatSettings {

	private final AiProvider provider;
	private final String systemPrompt;

	private final String openAiApiKey;
	private final String openAiBaseUrl;
	private final String openAiModel;

	private final String azureApiKey;
	private final String azureEndpoint;
	private final String azureDeployment;
	private final String azureModel;

	private final String anthropicApiKey;
	private final String anthropicModel;

	private final String ollamaBaseUrl;
	private final String ollamaModel;

	private ChatSettings(Builder builder) {
		this.provider = builder.provider;
		this.systemPrompt = builder.systemPrompt;
		this.openAiApiKey = builder.openAiApiKey;
		this.openAiBaseUrl = builder.openAiBaseUrl;
		this.openAiModel = builder.openAiModel;
		this.azureApiKey = builder.azureApiKey;
		this.azureEndpoint = builder.azureEndpoint;
		this.azureDeployment = builder.azureDeployment;
		this.azureModel = builder.azureModel;
		this.anthropicApiKey = builder.anthropicApiKey;
		this.anthropicModel = builder.anthropicModel;
		this.ollamaBaseUrl = builder.ollamaBaseUrl;
		this.ollamaModel = builder.ollamaModel;
	}

	public AiProvider provider() {
		return provider;
	}

	public String systemPrompt() {
		return systemPrompt;
	}

	public String openAiApiKey() {
		return openAiApiKey;
	}

	public String openAiBaseUrl() {
		return openAiBaseUrl;
	}

	public String openAiModel() {
		return openAiModel;
	}

	public String azureApiKey() {
		return azureApiKey;
	}

	public String azureEndpoint() {
		return azureEndpoint;
	}

	public String azureDeployment() {
		return azureDeployment;
	}

	public String azureModel() {
		return azureModel;
	}

	public String anthropicApiKey() {
		return anthropicApiKey;
	}

	public String anthropicModel() {
		return anthropicModel;
	}

	public String ollamaBaseUrl() {
		return ollamaBaseUrl;
	}

	public String ollamaModel() {
		return ollamaModel;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private AiProvider provider = AiProvider.OPENAI;
		private String systemPrompt;

		private String openAiApiKey;
		private String openAiBaseUrl;
		private String openAiModel;

		private String azureApiKey;
		private String azureEndpoint;
		private String azureDeployment;
		private String azureModel;

		private String anthropicApiKey;
		private String anthropicModel;

		private String ollamaBaseUrl;
		private String ollamaModel;

		private Builder() {
			// use builder()
		}

		public Builder provider(AiProvider provider) {
			if (provider != null) {
				this.provider = provider;
			}
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder openAiApiKey(String openAiApiKey) {
			this.openAiApiKey = openAiApiKey;
			return this;
		}

		public Builder openAiBaseUrl(String openAiBaseUrl) {
			this.openAiBaseUrl = openAiBaseUrl;
			return this;
		}

		public Builder openAiModel(String openAiModel) {
			this.openAiModel = openAiModel;
			return this;
		}

		public Builder azureApiKey(String azureApiKey) {
			this.azureApiKey = azureApiKey;
			return this;
		}

		public Builder azureEndpoint(String azureEndpoint) {
			this.azureEndpoint = azureEndpoint;
			return this;
		}

		public Builder azureDeployment(String azureDeployment) {
			this.azureDeployment = azureDeployment;
			return this;
		}

		public Builder azureModel(String azureModel) {
			this.azureModel = azureModel;
			return this;
		}

		public Builder anthropicApiKey(String anthropicApiKey) {
			this.anthropicApiKey = anthropicApiKey;
			return this;
		}

		public Builder anthropicModel(String anthropicModel) {
			this.anthropicModel = anthropicModel;
			return this;
		}

		public Builder ollamaBaseUrl(String ollamaBaseUrl) {
			this.ollamaBaseUrl = ollamaBaseUrl;
			return this;
		}

		public Builder ollamaModel(String ollamaModel) {
			this.ollamaModel = ollamaModel;
			return this;
		}

		public ChatSettings build() {
			return new ChatSettings(this);
		}
	}
}
