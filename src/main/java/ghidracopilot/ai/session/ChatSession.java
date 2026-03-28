package ghidracopilot.ai.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ghidracopilot.ai.ChatMessage;

/**
 * Represents a saved chat session that can be persisted to disk
 * and restored across Ghidra restarts.
 */
public class ChatSession {

	private String id;
	private long createdAt;
	private long updatedAt;
	private String providerId;
	private String modelId;
	private String programName;
	private String title;
	private List<ChatMessage> messages;

	public ChatSession() {
		this.id = UUID.randomUUID().toString();
		this.createdAt = System.currentTimeMillis();
		this.updatedAt = this.createdAt;
		this.messages = new ArrayList<>();
	}

	public ChatSession(String providerId, String modelId, String programName) {
		this();
		this.providerId = providerId;
		this.modelId = modelId;
		this.programName = programName;
	}

	/** Derive a title from the first user message. */
	public void deriveTitle() {
		if (title != null && !title.isBlank()) {
			return;
		}
		for (ChatMessage msg : messages) {
			if (msg.role() == ChatMessage.Role.USER) {
				String text = msg.content().trim();
				if (text.length() > 60) {
					text = text.substring(0, 57) + "...";
				}
				this.title = text;
				return;
			}
		}
		this.title = "New chat";
	}

	public void touch() {
		this.updatedAt = System.currentTimeMillis();
	}

	public void addMessage(ChatMessage message) {
		messages.add(message);
		touch();
	}

	// --- Getters/setters for Jackson ---

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	public long getCreatedAt() { return createdAt; }
	public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

	public long getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

	public String getProviderId() { return providerId; }
	public void setProviderId(String providerId) { this.providerId = providerId; }

	public String getModelId() { return modelId; }
	public void setModelId(String modelId) { this.modelId = modelId; }

	public String getProgramName() { return programName; }
	public void setProgramName(String programName) { this.programName = programName; }

	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }

	public List<ChatMessage> getMessages() { return messages; }
	public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
}
