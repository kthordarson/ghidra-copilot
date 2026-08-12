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
package ghidracopilot.ui.messages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ghidracopilot.ai.tools.results.ListItemsResult;
import ghidracopilot.ui.CopilotTheme;

/**
 * Compact tool invocation line inspired by Copilot CLI.
 * <p>
 * Layout (collapsed):
 * <pre>
 *   ● Decompile Function: at 0x00401000
 *     └ Decompiled 42 lines
 * </pre>
 * Layout (expanded — click to toggle):
 * <pre>
 *   ● Decompile Function: at 0x00401000
 *     ┌──────────────────────────────┐
 *     │ full result content          │
 *     └──────────────────────────────┘
 * </pre>
 */
public class ToolCallMessage extends AbstractChatMessage {

	private static final String DOT_FILLED = "\u25CF";   // ●
	private static final String CIRCLE_EMPTY = "\u25CB";  // ○
	private static final String CROSS = "\u2717";         // ✗
	private static final String CHILD_LAST = "\u2514";    // └
	private static final Pattern FOUND_COUNT_PATTERN =
		Pattern.compile("^Found\\s+(\\d+)\\s+([A-Za-z]+)\\.?$");
	private static final Pattern NO_MATCHING_PATTERN =
		Pattern.compile("^No matching\\s+([A-Za-z]+)s?\\s+found\\.?$");
	private static final Pattern AVAILABLE_COUNT_PATTERN =
		Pattern.compile("^Available\\s+([A-Za-z]+)s\\s+\\((\\d+)\\)$");
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	/** Human-readable display names for built-in tools (sentence case). */
	private static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
		Map.entry("run_analysis", "Run analysis"),
		Map.entry("reanalyze_function", "Re-analyze function"),
		Map.entry("list_analyzers", "List analyzers"),
		Map.entry("set_comment", "Set comment"),
		Map.entry("rename_label", "Rename label"),
		Map.entry("flag_address", "Flag address"),
		Map.entry("describe_call_graph", "Call graph"),
		Map.entry("describe_basic_block", "Basic block"),
		Map.entry("show_data_at_address", "Show data"),
		Map.entry("list_stack_variables", "List variables"),
		Map.entry("data_references_from_address", "Data references"),
		Map.entry("decompile_function", "Decompile function"),
		Map.entry("decompile_location", "Decompile location"),
		Map.entry("list_decompiled_lines", "Decompiled lines"),
		Map.entry("describe_decompiled_line", "Describe line"),
		Map.entry("comment_decompiled_line", "Comment line"),
		Map.entry("list_disassembly", "Disassembly"),
		Map.entry("navigate_to_address", "Navigate"),
		Map.entry("set_listing_cursor", "Set cursor"),
		Map.entry("read_bytes", "Read bytes"),
		Map.entry("patch_bytes", "Patch bytes"),
		Map.entry("fill_pattern", "Fill pattern"),
		Map.entry("dump_program_metadata", "Program metadata"),
		Map.entry("references_to_address", "References to"),
		Map.entry("references_from_address", "References from"),
		Map.entry("references_to_symbol", "Symbol references"),
		Map.entry("rename_function", "Rename function"),
		Map.entry("rename_stack_variable", "Rename variable"),
		Map.entry("retype_function", "Retype function"),
		Map.entry("describe_struct", "Describe struct"),
		Map.entry("find_struct", "Find struct"),
		Map.entry("list_structs", "List structs"),
		Map.entry("define_struct", "Define struct"),
		Map.entry("update_struct", "Update struct"),
		Map.entry("apply_struct_to_address", "Apply struct"),
		Map.entry("apply_struct_to_stack_variable", "Apply struct to variable"),
		Map.entry("list_strings", "List strings"),
		Map.entry("list_functions", "List functions"),
		Map.entry("list_imports", "List imports"),
		Map.entry("list_exports", "List exports"),
		Map.entry("list_labels", "List labels"),
		Map.entry("list_namespaces", "List namespaces"),
		Map.entry("list_classes", "List classes")
	);

	private final String toolName;
	private final JLabel iconLabel;
	private final JLabel displayNameLabel;
	private final JLabel intentLabel;
	private final JLabel calloutIconLabel;
	private final JLabel calloutTextLabel;
	private final JPanel calloutLine;
	private final JPanel detailPanel;
	private final JTextArea detailArea;
	private final javax.swing.Timer pulseTimer;
	private boolean expanded;
	private boolean pulseBright;
	private JPanel header;

	private ToolCallState state;
	private String intentionSummary;
	private String inputJson;
	private String outputJson;
	private String errorMessage;

	public ToolCallMessage(String toolName, String inputJson) {
		this(toolName, inputJson, null);
	}

	public ToolCallMessage(String toolName, String inputJson, String intentionSummary) {
		super("", CopilotTheme.toolText());
		this.toolName = toolName;
		this.intentionSummary = intentionSummary;
		this.inputJson = inputJson;
		this.outputJson = "";
		this.errorMessage = "";
		this.state = ToolCallState.INVOKED;
		this.expanded = false;

		// ghidra.util.Msg.debug(this, "[ToolCallMessage:" + toolName + "] CREATED" + ", args=" + (inputJson != null ? inputJson.length() + " chars" : "null") + ", argsPreview=" + truncateForLog(inputJson, 100));

		Color text = CopilotTheme.toolText();
		Color muted = CopilotTheme.systemText();
		Color detailBg = CopilotTheme.toolDetailBackground();
		Color detailBorder = CopilotTheme.toolDetailBorder();

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

		// --- Header line: [icon] DisplayName: intentionSummary ---
		header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		iconLabel = new JLabel(CIRCLE_EMPTY + " ");
		iconLabel.setFont(iconLabel.getFont().deriveFont(Font.PLAIN));
		header.add(iconLabel);

		displayNameLabel = new JLabel();
		displayNameLabel.setFont(displayNameLabel.getFont().deriveFont(Font.BOLD));
		displayNameLabel.setForeground(text);
		header.add(displayNameLabel);

		intentLabel = new JLabel();
		intentLabel.setFont(intentLabel.getFont().deriveFont(Font.PLAIN));
		intentLabel.setForeground(muted);
		header.add(intentLabel);

		MouseAdapter headerClick = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (hasExpandableContent()) toggleExpanded();
			}
		};
		header.addMouseListener(headerClick);
		iconLabel.addMouseListener(headerClick);
		displayNameLabel.addMouseListener(headerClick);
		intentLabel.addMouseListener(headerClick);
		add(header);

		// --- Callout line: └ result summary ---
		calloutLine = new JPanel(new BorderLayout());
		calloutLine.setOpaque(false);
		calloutLine.setAlignmentX(Component.LEFT_ALIGNMENT);
		calloutLine.setBorder(new EmptyBorder(0, 2, 0, 0));

		calloutIconLabel = new JLabel("  " + CHILD_LAST + " ");
		calloutIconLabel.setForeground(muted);
		calloutIconLabel.setFont(calloutIconLabel.getFont().deriveFont(Font.PLAIN));
		calloutLine.add(calloutIconLabel, BorderLayout.WEST);

		calloutTextLabel = new JLabel();
		calloutTextLabel.setForeground(muted);
		calloutTextLabel.setFont(calloutTextLabel.getFont().deriveFont(Font.PLAIN,
			calloutTextLabel.getFont().getSize2D() - 1f));
		calloutTextLabel.setVerticalAlignment(SwingConstants.TOP);
		calloutLine.add(calloutTextLabel, BorderLayout.CENTER);

		calloutLine.setVisible(false);
		MouseAdapter calloutClick = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (hasExpandableContent()) toggleExpanded();
			}
		};
		calloutLine.addMouseListener(calloutClick);
		calloutIconLabel.addMouseListener(calloutClick);
		calloutTextLabel.addMouseListener(calloutClick);
		add(calloutLine);

		// --- Expandable detail area (collapsed by default) ---
		detailArea = new JTextArea();
		detailArea.setEditable(false);
		detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		detailArea.setLineWrap(true);
		detailArea.setWrapStyleWord(true);
		detailArea.setOpaque(true);
		detailArea.setForeground(text);
		detailArea.setBackground(detailBg);
		detailArea.setMargin(new java.awt.Insets(4, 4, 4, 4));

		detailPanel = new JPanel(new BorderLayout());
		detailPanel.setOpaque(false);
		detailPanel.setBorder(new EmptyBorder(2, 16, 0, 0));
		detailPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JScrollPane detailScroll = new JScrollPane(detailArea) {
			@Override
			public java.awt.Dimension getPreferredSize() {
				java.awt.Dimension pref = super.getPreferredSize();
				pref.height = Math.min(pref.height, 300);
				return pref;
			}
			@Override
			public java.awt.Dimension getMaximumSize() {
				java.awt.Dimension max = super.getMaximumSize();
				max.height = 300;
				return max;
			}
		};
		detailScroll.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(detailBorder, 1, true),
			new EmptyBorder(6, 8, 6, 8)));
		detailScroll.getViewport().setBackground(detailBg);
		detailScroll.setOpaque(false);
		detailPanel.add(detailScroll, BorderLayout.CENTER);
		detailPanel.setVisible(false);
		add(detailPanel);

		pulseTimer = new javax.swing.Timer(500, e -> {
			pulseBright = !pulseBright;
			Color base = CopilotTheme.stateInProgress();
			iconLabel.setForeground(pulseBright ? base : base.darker());
		});
		pulseTimer.setRepeats(true);

		updateDisplay();
	}

	public void setState(ToolCallState newState) {
		if (newState == null) {
			return;
		}
		// ghidra.util.Msg.debug(this, "[ToolCallMessage:" + toolName + "] setState: " + state + " → " + newState + ", hasOutput=" + (!outputJson.isEmpty()) + ", hasError=" + (!errorMessage.isEmpty()) + ", expandable=" + hasExpandableContent());
		this.state = newState;
		updateDisplay();
	}

	public void setIntentionSummary(String summary) {
		this.intentionSummary = summary;
		updateDisplay();
	}

	public void setOutputJson(String outputJson) {
		this.outputJson = outputJson != null ? outputJson : "";
		// ghidra.util.Msg.debug(this, "[ToolCallMessage:" + toolName + "] setOutputJson called, " + "length=" + this.outputJson.length() + ", preview=" + truncate(this.outputJson, 120));
		updateDetailArea();
		updateCallout();
		updateExpandability();
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage != null ? errorMessage : "";
		ghidra.util.Msg.debug(this, "[ToolCallMessage:" + toolName + "] setErrorMessage called, " + "length=" + this.errorMessage.length());
		updateDisplay();
		updateExpandability();
	}

	private boolean hasExpandableContent() {
		return (outputJson != null && !outputJson.isBlank())
			|| (errorMessage != null && !errorMessage.isBlank());
	}

	private void updateExpandability() {
		Cursor cursor = hasExpandableContent()
			? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
			: Cursor.getDefaultCursor();
		header.setCursor(cursor);
		iconLabel.setCursor(cursor);
		displayNameLabel.setCursor(cursor);
		intentLabel.setCursor(cursor);
		calloutLine.setCursor(cursor);
		calloutIconLabel.setCursor(cursor);
		calloutTextLabel.setCursor(cursor);
	}

	private void toggleExpanded() {
		expanded = !expanded;
		updateDetailArea();
		detailPanel.setVisible(expanded);
		if (expanded) {
			calloutLine.setVisible(false);
		} else {
			updateCallout();
		}
		// Propagate layout change up through the scroll pane
		java.awt.Container parent = getParent();
		while (parent != null) {
			parent.revalidate();
			parent.repaint();
			parent = parent.getParent();
		}
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private void updateDisplay() {
		String icon;
		Color iconColor;
		switch (state) {
			case INVOKED -> {
				icon = CIRCLE_EMPTY;
				iconColor = CopilotTheme.systemText();
				pulseTimer.stop();
			}
			case IN_PROGRESS -> {
				icon = CIRCLE_EMPTY;
				iconColor = CopilotTheme.stateInProgress();
				pulseBright = true;
				pulseTimer.start();
			}
			case COMPLETED -> {
				icon = DOT_FILLED;
				iconColor = CopilotTheme.stateCompleted();
				pulseTimer.stop();
			}
			case FAILED -> {
				icon = CROSS;
				iconColor = CopilotTheme.stateFailed();
				pulseTimer.stop();
			}
			default -> {
				icon = CIRCLE_EMPTY;
				iconColor = CopilotTheme.systemText();
				pulseTimer.stop();
			}
		}
		iconLabel.setText(icon + " ");
		iconLabel.setForeground(iconColor);

		String display = DISPLAY_NAMES.getOrDefault(toolName, prettifyToolName(toolName));
		displayNameLabel.setText(display);

		String headerContext = buildHeaderContext();
		if (headerContext != null && !headerContext.isBlank()) {
			intentLabel.setText(" " + headerContext);
			intentLabel.setVisible(true);
		} else {
			intentLabel.setText("");
			intentLabel.setVisible(false);
		}

		updateCallout();
	}

	private void updateCallout() {
		boolean wasVisible = calloutLine.isVisible();
		if (expanded) {
			calloutTextLabel.setText("");
			calloutLine.setVisible(false);
		}
		else if (state == ToolCallState.FAILED && !errorMessage.isEmpty()) {
			String truncated = errorMessage.length() > 80
				? errorMessage.substring(0, 77) + "..."
				: errorMessage;
			calloutTextLabel.setText(truncated);
			calloutTextLabel.setForeground(CopilotTheme.stateFailed());
			calloutIconLabel.setForeground(CopilotTheme.stateFailed());
			calloutLine.setVisible(true);
		}
		else if (state == ToolCallState.COMPLETED && !outputJson.isEmpty()) {
			String summary = buildResultSummary();
			// ghidra.util.Msg.debug(this, "[ToolCallMessage:" + toolName + "] updateCallout: " + "state=" + state + ", outputLen=" + outputJson.length() + ", summary=" + (summary != null ? "'" + summary + "'" : "null"));
			if (summary != null && !summary.isEmpty()) {
				calloutTextLabel.setText(summary);
				calloutTextLabel.setForeground(CopilotTheme.systemText());
				calloutIconLabel.setForeground(CopilotTheme.systemText());
				calloutLine.setVisible(true);
			} else {
				calloutTextLabel.setText("");
				calloutLine.setVisible(false);
			}
		}
		else {
			// ghidra.util.Msg.debug(this, "[ToolCallMessage:" + toolName + "] updateCallout: "
			// 	+ "state=" + state + ", outputLen=" + outputJson.length()
			// 	+ ", errorLen=" + errorMessage.length() + " → hidden");
			calloutTextLabel.setText("");
			calloutLine.setVisible(false);
		}

		// Force layout refresh when visibility changes
		if (calloutLine.isVisible() != wasVisible) {
			revalidateUpTree();
		}
		else {
			calloutLine.revalidate();
			calloutLine.repaint();
			revalidateUpTree();
		}
	}

	// ── Arg parsing ──────────────────────────────────────────────────────

	private JsonNode parsedArgs;
	private boolean argsParsed;

	/** Lazily parse inputJson once via Jackson, with Map.toString() fallback. */
	private JsonNode args() {
		if (!argsParsed) {
			argsParsed = true;
			if (inputJson != null && !inputJson.isBlank()) {
				try {
					parsedArgs = MAPPER.readTree(inputJson);
				} catch (Exception e1) {
					// Spring AI often returns Map.toString() format: {key=val, key2=val2}
					try {
						String converted = mapToStringToJson(inputJson);
						parsedArgs = MAPPER.readTree(converted);
					} catch (Exception ignored) { /* truly malformed */ }
				}
			}
		}
		return parsedArgs;
	}

	/**
	 * Convert Java Map.toString() format to JSON.
	 * Input:  {addressText=100000f38, length=64, includeBytes=true}
	 * Output: {"addressText":"100000f38","length":"64","includeBytes":"true"}
	 */
	private static String mapToStringToJson(String input) {
		String s = input.trim();
		if (!s.startsWith("{") || !s.endsWith("}")) return input;
		s = s.substring(1, s.length() - 1).trim();
		if (s.isEmpty()) return "{}";

		StringBuilder json = new StringBuilder("{");
		boolean first = true;
		for (String pair : s.split(",\\s*")) {
			int eq = pair.indexOf('=');
			if (eq < 0) continue;
			String key = pair.substring(0, eq).trim();
			String val = pair.substring(eq + 1).trim();
			if (!first) json.append(',');
			json.append('"').append(escapeJson(key)).append("\":\"")
				.append(escapeJson(val)).append('"');
			first = false;
		}
		json.append('}');
		return json.toString();
	}

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String arg(String key) {
		JsonNode n = args();
		if (n == null || !n.has(key)) return null;
		JsonNode child = n.get(key);
		if (child.isTextual()) {
			String v = child.asText().trim();
			return v.isEmpty() ? null : v;
		}
		if (child.isNumber()) return String.valueOf(child.asLong());
		if (child.isBoolean()) return String.valueOf(child.asBoolean());
		return null;
	}

	// ── Header context ───────────────────────────────────────────────────

	/**
	 * Builds the parenthetical context shown after the tool name in the header.
	 * Parses inputJson directly with Jackson — no dependency on IntentionSummariser.
	 *
	 * Examples:
	 *   Decompile function (0x100000b4)
	 *   Show data (0x100000b4) 64 bytes
	 *   Rename function (0x401000) → process_data
	 *   List functions "main"
	 *   Describe struct (MY_STRUCT)
	 */
	private String buildHeaderContext() {
		if (args() == null || args().isEmpty()) return null;

		return switch (toolName) {
			// ── Address-centric ──────────────────────────────────────────
			case "decompile_function", "decompile_location",
				"navigate_to_address", "set_listing_cursor",
				"describe_basic_block", "references_to_address",
				"references_from_address", "data_references_from_address",
				"list_stack_variables", "reanalyze_function" ->
				paren(addr(arg("addressText")));

			case "show_data_at_address" -> {
				String a = addr(arg("addressText"));
				yield a != null ? "(" + a + ")" : null;
			}

			case "read_bytes" -> {
				String a = addr(arg("addressText"));
				String len = arg("length");
				if (a == null) yield null;
				yield len != null ? "(" + a + ") " + len + " bytes" : "(" + a + ")";
			}

			case "list_disassembly" -> {
				String a = addr(arg("addressText"));
				String cnt = arg("maxInstructions");
				if (a == null) yield null;
				yield cnt != null ? "(" + a + ") " + cnt + " insns" : "(" + a + ")";
			}

			case "list_decompiled_lines" -> {
				String a = addr(arg("addressText"));
				String start = arg("startLine");
				String end = arg("endLine");
				if (a == null) yield null;
				if (start != null && end != null) {
					yield "(" + a + ") lines " + start + "–" + end;
				}
				yield "(" + a + ")";
			}

			// ── Rename / retype ──────────────────────────────────────────
			case "rename_function" -> {
				String a = addr(arg("addressText"));
				String name = arg("newName");
				if (a != null && name != null) yield "(" + a + ") → " + name;
				if (name != null) yield "→ " + name;
				yield paren(a);
			}

			case "rename_stack_variable" -> {
				String cur = arg("currentName");
				String nw = arg("newName");
				if (cur != null && nw != null) yield cur + " → " + nw;
				if (nw != null) yield "→ " + nw;
				yield paren(addr(arg("addressText")));
			}

			case "retype_function" -> {
				String a = addr(arg("addressText"));
				String sig = arg("signatureText");
				if (sig != null) yield truncate(sig, 60);
				yield paren(a);
			}

			case "rename_label" -> {
				String a = addr(arg("addressText"));
				String name = arg("newName");
				if (a != null && name != null) yield "(" + a + ") → " + name;
				yield paren(a);
			}

			// ── Search / list ────────────────────────────────────────────
			case "list_functions", "list_imports", "list_exports",
				"list_labels", "list_namespaces", "list_classes" -> {
				String f = arg("nameContains");
				yield f != null ? "\"" + f + "\"" : null;
			}

			case "list_strings" -> {
				String f = arg("contains");
				yield f != null ? "\"" + f + "\"" : null;
			}

			case "references_to_symbol" -> paren(arg("symbolName"));

			// ── Comments / annotation ────────────────────────────────────
			case "set_comment" -> {
				String a = addr(arg("addressText"));
				String type = arg("commentType");
				if (a != null && type != null) yield type + " (" + a + ")";
				yield paren(a);
			}

			case "describe_decompiled_line" -> {
				String a = addr(arg("addressText"));
				String line = arg("lineNumber");
				if (a != null && line != null) yield "(" + a + ") line " + line;
				yield paren(a);
			}

			case "comment_decompiled_line" -> {
				String a = addr(arg("addressText"));
				String line = arg("lineNumber");
				if (a != null && line != null) yield "(" + a + ") line " + line;
				yield paren(a);
			}

			case "flag_address" -> {
				String a = addr(arg("addressText"));
				String cat = arg("category");
				if (a != null && cat != null) yield cat + " (" + a + ")";
				yield paren(a);
			}

			// ── Structs ──────────────────────────────────────────────────
			case "describe_struct", "find_struct" -> paren(arg("name"));
			case "define_struct", "update_struct" -> paren(arg("name"));

			case "list_structs" -> {
				String cat = arg("categoryPath");
				yield cat != null ? "(" + cat + ")" : null;
			}

			case "apply_struct_to_address" -> {
				String s = arg("structName");
				String a = addr(arg("addressText"));
				if (s != null && a != null) yield s + " (" + a + ")";
				yield s != null ? paren(s) : paren(a);
			}

			case "apply_struct_to_stack_variable" -> {
				String s = arg("structName");
				String v = arg("variableName");
				if (s != null && v != null) yield s + " → " + v;
				yield paren(s);
			}

			// ── Patch ────────────────────────────────────────────────────
			case "patch_bytes" -> {
				String a = addr(arg("addressText"));
				String hex = arg("hexBytes");
				if (a != null && hex != null) yield "(" + a + ") " + truncate(hex, 24);
				yield paren(a);
			}

			case "fill_pattern" -> paren(addr(arg("addressText")));

			// ── Call graph ───────────────────────────────────────────────
			case "describe_call_graph" -> {
				String a = addr(arg("addressText"));
				String depth = arg("maxCallees");
				if (a != null && depth != null) yield "(" + a + ") depth " + depth;
				yield paren(a);
			}

			// ── Analysis ─────────────────────────────────────────────────
			case "run_analysis" -> {
				String names = arg("analyzerNames");
				yield names != null ? truncate(names, 50) : null;
			}

			case "dump_program_metadata" -> null;

			// ── Fallback ─────────────────────────────────────────────────
			default -> {
				// Try common field names
				String a = arg("addressText");
				if (a != null) yield "(" + a + ")";
				String name = arg("name");
				if (name != null) yield "(" + name + ")";
				yield null;
			}
		};
	}

	// ── Result summary (callout line) ────────────────────────────────────

	/** Concise result summary that avoids repeating header info. */
	String buildResultSummary() {
		if (outputJson == null || outputJson.isBlank()) return null;
		String text = outputJson.strip();
		String custom = buildToolSpecificResultSummary(text);
		if (custom != null) return custom;
		String envelopeSummary = buildEnvelopeMessageSummary(text);
		if (envelopeSummary != null) return envelopeSummary;

		String[] lines = text.split("\\R");
		int lineCount = lines.length;
		String firstNonBlankLine = null;
		for (String line : lines) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				firstNonBlankLine = trimmed;
				break;
			}
		}

		// Multi-line: just the count
		if (lineCount > 2) return lineCount + " lines";

		// Single/two-line: show the first non-blank line if present.
		if (firstNonBlankLine == null) {
			return "Completed";
		}
		if (firstNonBlankLine.length() > 80) {
			firstNonBlankLine = firstNonBlankLine.substring(0, 77) + "...";
		}
		return firstNonBlankLine;
	}

	private String buildToolSpecificResultSummary(String text) {
		return switch (toolName) {
			case "list_functions", "list_imports", "list_exports", "list_labels",
				"list_namespaces", "list_classes", "list_strings", "list_analyzers" ->
				buildListResultSummary(text);
			default -> null;
		};
	}

	private String buildListResultSummary(String text) {
		String structured = buildStructuredListResultSummary(text);
		if (structured != null) {
			return structured;
		}

		String[] lines = text.split("\\R");
		String header = firstNonBlankLine(lines);
		if (header == null) {
			return null;
		}

		Matcher noMatch = NO_MATCHING_PATTERN.matcher(header);
		if (noMatch.matches()) {
			String singular = singularize(noMatch.group(1));
			return "No " + singular + "s";
		}

		Matcher found = FOUND_COUNT_PATTERN.matcher(header);
		Matcher available = AVAILABLE_COUNT_PATTERN.matcher(header);
		if (!found.matches()) {
			if (available.matches()) {
				int count = Integer.parseInt(available.group(2));
				String singularType = singularize(available.group(1));
				return summarizeListItems(count, singularType, extractListItems(lines), false);
			}
			return null;
		}

		int count = Integer.parseInt(found.group(1));
		String pluralType = found.group(2).toLowerCase();
		return summarizeListItems(count, singularize(pluralType), extractListItems(lines), false);
	}

	private List<String> extractListItems(String[] lines) {
		List<String> items = new ArrayList<>();
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) {
				continue;
			}
			String item = extractListItem(line);
			if (item != null && !item.isBlank()) {
				items.add(item);
			}
		}
		return items;
	}

	private String extractListItem(String line) {
		return switch (toolName) {
			case "list_functions", "list_exports", "list_labels",
				"list_namespaces", "list_classes" -> {
				int sep = line.indexOf(" : ");
				yield sep >= 0 ? line.substring(sep + 3).trim() : line;
			}
			case "list_strings" -> {
				int sep = line.indexOf(" : ");
				yield sep >= 0 ? line.substring(sep + 3).trim() : line;
			}
			case "list_imports", "list_analyzers" -> line;
			default -> line;
		};
	}

	private String buildStructuredListResultSummary(String text) {
		JsonNode dataNode = extractStructuredDataNode(text);
		if (dataNode == null || !dataNode.isObject()) {
			return null;
		}
		try {
			ListItemsResult result = JSON_MAPPER.treeToValue(dataNode, ListItemsResult.class);
			return result.summary();
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private String buildEnvelopeMessageSummary(String text) {
		ghidracopilot.ai.tools.ToolResult result = ghidracopilot.ai.tools.ToolResult.tryParse(text);
		if (result == null) {
			return null;
		}
		String summary = result.toolSummary();
		if (summary != null && !summary.isBlank()) {
			return truncate(summary, 96);
		}
		if (!result.success()) {
			String error = result.errorMessage();
			return (error == null || error.isBlank()) ? "Failed" : truncate(error, 96);
		}
		String message = result.message();
		return (message == null || message.isBlank()) ? "Completed" : truncate(message, 96);
	}

	private JsonNode extractStructuredDataNode(String text) {
		JsonNode root = extractToolResultEnvelope(text);
		return root != null ? root.get("data") : null;
	}

	private JsonNode extractToolResultEnvelope(String text) {
		if (text == null || text.isBlank() || text.charAt(0) != '{') {
			return null;
		}
		try {
			JsonNode root = JSON_MAPPER.readTree(text);
			if (!root.isObject() || !root.has("success") || !root.has("message")) {
				return null;
			}
			return root;
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private String summarizeListItems(int count, String singularType, List<String> items, boolean truncatedItems) {
		String typeLabel = count == 1 ? singularize(singularType) : pluralize(singularType);
		if (count == 0 || items.isEmpty()) {
			return count == 0 ? "No " + pluralize(singularType) : count + " " + typeLabel;
		}

		int shown = Math.min(items.size(), 2);
		String joined = String.join(", ", items.subList(0, shown));
		if (count > shown || truncatedItems) {
			joined += ", ...";
		}
		return truncate(count + " " + typeLabel + ": " + joined, 96);
	}

	private static String firstNonBlankLine(String[] lines) {
		for (String line : lines) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				return trimmed;
			}
		}
		return null;
	}

	private static String singularize(String plural) {
		if (plural == null || plural.isBlank()) {
			return "item";
		}
		String word = plural.toLowerCase();
		if (word.endsWith("sses")) return word.substring(0, word.length() - 2);
		if (word.endsWith("ies") && word.length() > 3) return word.substring(0, word.length() - 3) + "y";
		if (word.endsWith("s") && word.length() > 1) return word.substring(0, word.length() - 1);
		return word;
	}

	private static String pluralize(String singular) {
		if (singular == null || singular.isBlank()) {
			return "items";
		}
		String word = singularize(singular);
		if (word.endsWith("y") && word.length() > 1) return word.substring(0, word.length() - 1) + "ies";
		if (word.endsWith("s")) return word + "es";
		return word + "s";
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private static String paren(String value) {
		return value != null && !value.isBlank() ? "(" + value + ")" : null;
	}

	/** Ensures a hex address has a 0x prefix for display. */
	private static String addr(String value) {
		if (value == null || value.isBlank()) return null;
		String v = value.trim();
		if (v.startsWith("0x") || v.startsWith("0X")) return v;
		// Only prefix if it looks like hex (all hex chars)
		if (v.matches("[0-9a-fA-F]+")) return "0x" + v;
		return v;
	}

	private static String truncate(String value, int maxLen) {
		if (value == null) return null;
		return value.length() > maxLen ? value.substring(0, maxLen - 3) + "..." : value;
	}

	private static String truncateForLog(String value, int maxLen) {
		if (value == null) return "null";
		if (value.length() <= maxLen) return "'" + value + "'";
		return "'" + value.substring(0, maxLen - 3) + "...'";
	}

	private void updateDetailArea() {
		StringBuilder sb = new StringBuilder();
		sb.append("Tool: ").append(toolName).append('\n');
		sb.append("Input:\n");
		sb.append(inputJson == null || inputJson.isEmpty() ? "(none)" : inputJson);

		if (!outputJson.isEmpty()) {
			sb.append("\n\nOutput:\n");
			String formatted = formatOutputForDetail(outputJson);
			String truncated = formatted.length() > 4000
				? formatted.substring(0, 4000) + "\n... (truncated)"
				: formatted;
			sb.append(truncated);
		}

		if (state == ToolCallState.FAILED && !errorMessage.isEmpty()) {
			sb.append("\n\nError:\n").append(errorMessage);
		}

		detailArea.setText(sb.toString());
		detailArea.setCaretPosition(0);
	}

	private String formatOutputForDetail(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		try {
			JsonNode node = JSON_MAPPER.readTree(value);
			return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
		}
		catch (Exception ignored) {
			return value;
		}
	}

	/** Returns the human-readable display name for a tool. */
	public static String displayNameFor(String toolName) {
		return DISPLAY_NAMES.getOrDefault(toolName, prettifyToolName(toolName));
	}

	/** Convert snake_case tool name to sentence case display name. */
	private static String prettifyToolName(String name) {
		if (name == null || name.isEmpty()) return name;
		String[] parts = name.split("_");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			if (i == 0) {
				sb.append(Character.toUpperCase(part.charAt(0)));
				if (part.length() > 1) sb.append(part.substring(1));
			} else {
				sb.append(part);
			}
		}
		return sb.toString();
	}
}
