package ghidracopilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.copilot.sdk.events.AssistantMessageEvent;

class CopilotSdkChatServiceTest {

	@Test
	void extractsIntentFromBuiltInReportIntentToolRequest() {
		var reportIntent = new AssistantMessageEvent.AssistantMessageData.ToolRequest(
			"call-1",
			"report_intent",
			Map.of("intent", "Analyzing program entry point"));
		var otherTool = new AssistantMessageEvent.AssistantMessageData.ToolRequest(
			"call-2",
			"dump_program_metadata",
			Map.of());
		var data = new AssistantMessageEvent.AssistantMessageData(
			"msg-1",
			"\n\n",
			List.of(reportIntent, otherTool),
			null,
			null,
			null,
			null,
			null);

		assertEquals(
			"Analyzing program entry point",
			CopilotSdkChatService.extractReportIntent(data));
	}

	@Test
	void ignoresAssistantMessagesWithoutReportIntentToolRequest() {
		var otherTool = new AssistantMessageEvent.AssistantMessageData.ToolRequest(
			"call-2",
			"dump_program_metadata",
			Map.of());
		var data = new AssistantMessageEvent.AssistantMessageData(
			"msg-1",
			"\n\n",
			List.of(otherTool),
			null,
			null,
			null,
			null,
			null);

		assertNull(CopilotSdkChatService.extractReportIntent(data));
	}
}
