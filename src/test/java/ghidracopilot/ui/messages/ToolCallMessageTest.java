package ghidracopilot.ui.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ghidracopilot.ai.tools.ToolResult;
import ghidracopilot.ai.tools.results.ListItemsResult;
import ghidracopilot.ai.tools.results.ListItemsResult.Item;

class ToolCallMessageTest {

	@Test
	void listLabelsSummaryIncludesConcreteMatches() {
		ToolCallMessage message = new ToolCallMessage(
			"list_labels",
			"{nameContains=demo_agent4main, maxResults=5}");

		message.setOutputJson(new ToolResult(true, "Found 2 labels.",
			new ListItemsResult("label", 2, false, java.util.List.of(
				new Item("__ZN10demo_agent4main17h8a8a58dccbf35990E",
					"__ZN10demo_agent4main17h8a8a58dccbf35990E", "0x100000bf0"),
				new Item("s___ZN10demo_agent4main17h8a8a58dc_1036be37c",
					"s___ZN10demo_agent4main17h8a8a58dc_1036be37c", "0x1036be37c")))).toJson());
		message.setState(ToolCallState.COMPLETED);

		assertEquals(
			"2 labels: __ZN10demo_agent4main17h8a8a58dccbf35990E, s___ZN10demo_agent4main17h8a8a58dc_1036b...",
			message.buildResultSummary());
	}

	@Test
	void listFunctionsNoMatchSummaryIsCondensed() {
		ToolCallMessage message = new ToolCallMessage(
			"list_functions",
			"{nameContains=missing, maxResults=5}");

		message.setOutputJson("No matching functions found.");
		message.setState(ToolCallState.COMPLETED);

		assertEquals("No functions", message.buildResultSummary());
	}

	@Test
	void listFunctionsSingleMatchSummaryShowsFunctionName() {
		ToolCallMessage message = new ToolCallMessage(
			"list_functions",
			"{nameContains=entry, maxResults=10}");

		message.setOutputJson("""
			Found 1 functions.
			0x100000f38 : entry
			""");
		message.setState(ToolCallState.COMPLETED);

		assertEquals("1 function: entry", message.buildResultSummary());
	}

	@Test
	void listAnalyzersSummaryUsesStructuredPayload() {
		ToolCallMessage message = new ToolCallMessage("list_analyzers", "{}");

		message.setOutputJson(new ToolResult(true, "Available analyzers (3)",
			new ListItemsResult("analyzer", 3, false, java.util.List.of(
				new Item("Decompiler Parameter ID", "Decompiler Parameter ID", null),
				new Item("Stack", "Stack", null),
				new Item("Reference", "Reference", null)))).toJson());
		message.setState(ToolCallState.COMPLETED);

		assertEquals("3 analyzers: Decompiler Parameter ID, Stack, ...", message.buildResultSummary());
	}

	@Test
	void genericJsonEnvelopeUsesMessageForPreview() {
		ToolCallMessage message = new ToolCallMessage("show_data_at_address", "{}");

		message.setOutputJson(new ToolResult(true,
			"Read 64 byte(s) starting at 0x100000f39",
			"Decoded (UTF-8, null-terminated scan): <non-printable>").toJson());
		message.setState(ToolCallState.COMPLETED);

		assertEquals("Read 64 byte(s) starting at 0x100000f39", message.buildResultSummary());
	}

	@Test
	void failedJsonEnvelopeUsesErrorMessageForPreview() {
		ToolCallMessage message = new ToolCallMessage("list_disassembly", "{}");

		message.setOutputJson(new ToolResult(false,
			"No disassembly available for function entry",
			null).toJson());
		message.setState(ToolCallState.COMPLETED);

		assertEquals("No disassembly available for function entry", message.buildResultSummary());
	}
}
