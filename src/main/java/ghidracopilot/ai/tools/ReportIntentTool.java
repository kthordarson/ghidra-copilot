package ghidracopilot.ai.tools;

import java.util.function.Consumer;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Tool that lets the model report what it is currently doing.
 * The intent text is displayed in the UI's intent strip rather than
 * appearing as a normal tool call message.
 */
public class ReportIntentTool {

	/** Name used to identify this tool in the UI suppression logic. */
	public static final String TOOL_NAME = "report_intent";

	private volatile Consumer<String> intentListener;

	public void setIntentListener(Consumer<String> listener) {
		this.intentListener = listener;
	}

	@Tool(name = TOOL_NAME,
		description = "Update the current intent displayed to the user. "
			+ "Call this to keep the user informed about what you are doing. "
			+ "Use short, gerund-form descriptions (e.g., 'Analyzing function', "
			+ "'Renaming variables', 'Searching for references').")
	public ToolResult reportIntent(
			@ToolParam(description = "A short description of what you are currently doing, "
				+ "4 words max, gerund form.")
			String intent) {
		Consumer<String> listener = this.intentListener;
		if (listener != null && intent != null && !intent.isBlank()) {
			listener.accept(intent.trim());
		}
		return ToolResult.success("Intent updated.");
	}
}
