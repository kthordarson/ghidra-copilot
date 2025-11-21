package ghidracopilot.ai;

/**
 * Describes how assertive the Copilot should be when handling user prompts.
 */
public enum InteractionMode {
	/**
	 * Read-only assistance; avoid any actions that modify the program.
	 */
	ASK,
	/**
	 * Full autonomy; the agent is encouraged to make changes that improve clarity.
	 */
	AGENT;

	public boolean isReadOnly() {
		return this == ASK;
	}
}
