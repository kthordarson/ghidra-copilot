package ghidracopilot.ai;

import java.util.Objects;

/**
 * Snapshot of the current state of a tool call that can be surfaced to the UI.
 *
 * @param id unique identifier assigned by the model for the tool call
 * @param toolName registered name of the tool being invoked
 * @param argumentsJson raw JSON arguments supplied by the model (may be {@code null})
 * @param outputJson raw JSON output produced by the tool (may be {@code null})
 * @param state new lifecycle state for the tool
 * @param errorMessage optional error description when {@link State#FAILED}
 */
public record ToolCallUpdate(
		String id,
		String toolName,
		String argumentsJson,
		String outputJson,
		State state,
		String errorMessage) {

	public ToolCallUpdate {
		id = Objects.requireNonNull(id, "id must not be null");
		toolName = Objects.requireNonNull(toolName, "toolName must not be null");
		state = Objects.requireNonNull(state, "state must not be null");
	}

	public enum State {
		INVOKED,
		IN_PROGRESS,
		COMPLETED,
		FAILED
	}

	public ToolCallUpdate withState(State newState) {
		return new ToolCallUpdate(id, toolName, argumentsJson, outputJson, newState, errorMessage);
	}

	public ToolCallUpdate withOutput(String jsonOutput) {
		return new ToolCallUpdate(id, toolName, argumentsJson, jsonOutput, state, errorMessage);
	}

	public ToolCallUpdate withError(String message) {
		return new ToolCallUpdate(id, toolName, argumentsJson, outputJson, state, message);
	}
}

