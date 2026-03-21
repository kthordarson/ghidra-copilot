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
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

		header.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (hasExpandableContent()) toggleExpanded();
			}
		});
		add(header);

		// --- Callout line: └ result summary ---
		calloutLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		calloutLine.setOpaque(false);
		calloutLine.setAlignmentX(Component.LEFT_ALIGNMENT);
		calloutLine.setBorder(new EmptyBorder(0, 2, 0, 0));

		calloutIconLabel = new JLabel("  " + CHILD_LAST + " ");
		calloutIconLabel.setForeground(muted);
		calloutIconLabel.setFont(calloutIconLabel.getFont().deriveFont(Font.PLAIN));
		calloutLine.add(calloutIconLabel);

		calloutTextLabel = new JLabel();
		calloutTextLabel.setForeground(muted);
		calloutTextLabel.setFont(calloutTextLabel.getFont().deriveFont(Font.PLAIN,
			calloutTextLabel.getFont().getSize2D() - 1f));
		calloutLine.add(calloutTextLabel);

		calloutLine.setVisible(false);
		calloutLine.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (hasExpandableContent()) toggleExpanded();
			}
		});
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
		this.state = newState;
		updateDisplay();
	}

	public void setIntentionSummary(String summary) {
		this.intentionSummary = summary;
		updateDisplay();
	}

	public void setOutputJson(String outputJson) {
		this.outputJson = outputJson != null ? outputJson : "";
		updateDetailArea();
		updateCallout();
		updateExpandability();
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage != null ? errorMessage : "";
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
		calloutLine.setCursor(cursor);
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
		if (expanded) {
			calloutLine.setVisible(false);
			return;
		}
		if (state == ToolCallState.FAILED && !errorMessage.isEmpty()) {
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
			if (summary != null && !summary.isEmpty()) {
				calloutTextLabel.setText(summary);
				calloutTextLabel.setForeground(CopilotTheme.systemText());
				calloutIconLabel.setForeground(CopilotTheme.systemText());
				calloutLine.setVisible(true);
			} else {
				calloutLine.setVisible(false);
			}
		}
		else {
			calloutLine.setVisible(false);
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
				paren(arg("addressText"));

			case "show_data_at_address" -> {
				String a = arg("addressText");
				yield a != null ? "(" + a + ")" : null;
			}

			case "read_bytes" -> {
				String a = arg("addressText");
				String len = arg("length");
				if (a == null) yield null;
				yield len != null ? "(" + a + ") " + len + " bytes" : "(" + a + ")";
			}

			case "list_disassembly" -> {
				String a = arg("addressText");
				String cnt = arg("maxInstructions");
				if (a == null) yield null;
				yield cnt != null ? "(" + a + ") " + cnt + " insns" : "(" + a + ")";
			}

			case "list_decompiled_lines" -> {
				String a = arg("addressText");
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
				String a = arg("addressText");
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
				yield paren(arg("addressText"));
			}

			case "retype_function" -> {
				String a = arg("addressText");
				String sig = arg("signatureText");
				if (sig != null) yield truncate(sig, 60);
				yield paren(a);
			}

			case "rename_label" -> {
				String a = arg("addressText");
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
				String a = arg("addressText");
				String type = arg("commentType");
				if (a != null && type != null) yield type + " (" + a + ")";
				yield paren(a);
			}

			case "describe_decompiled_line" -> {
				String a = arg("addressText");
				String line = arg("lineNumber");
				if (a != null && line != null) yield "(" + a + ") line " + line;
				yield paren(a);
			}

			case "comment_decompiled_line" -> {
				String a = arg("addressText");
				String line = arg("lineNumber");
				if (a != null && line != null) yield "(" + a + ") line " + line;
				yield paren(a);
			}

			case "flag_address" -> {
				String a = arg("addressText");
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
				String a = arg("addressText");
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
				String a = arg("addressText");
				String hex = arg("hexBytes");
				if (a != null && hex != null) yield "(" + a + ") " + truncate(hex, 24);
				yield paren(a);
			}

			case "fill_pattern" -> paren(arg("addressText"));

			// ── Call graph ───────────────────────────────────────────────
			case "describe_call_graph" -> {
				String a = arg("addressText");
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
	private String buildResultSummary() {
		if (outputJson == null || outputJson.isEmpty()) return null;
		String text = outputJson.trim();

		int lineCount = 1;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') lineCount++;
		}

		// Multi-line: just the count
		if (lineCount > 2) return lineCount + " lines";

		// Single/two-line: show first line, skip if it just echoes the header
		String firstLine = text.contains("\n")
			? text.substring(0, text.indexOf('\n')).trim() : text;
		if (firstLine.length() > 80) {
			firstLine = firstLine.substring(0, 77) + "...";
		}
		return firstLine;
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private static String paren(String value) {
		return value != null && !value.isBlank() ? "(" + value + ")" : null;
	}

	private static String truncate(String value, int maxLen) {
		if (value == null) return null;
		return value.length() > maxLen ? value.substring(0, maxLen - 3) + "..." : value;
	}

	private void updateDetailArea() {
		StringBuilder sb = new StringBuilder();
		sb.append("Tool: ").append(toolName).append('\n');
		sb.append("Input:\n");
		sb.append(inputJson == null || inputJson.isEmpty() ? "(none)" : inputJson);

		if (!outputJson.isEmpty()) {
			sb.append("\n\nOutput:\n");
			String truncated = outputJson.length() > 4000
				? outputJson.substring(0, 4000) + "\n... (truncated)"
				: outputJson;
			sb.append(truncated);
		}

		if (state == ToolCallState.FAILED && !errorMessage.isEmpty()) {
			sb.append("\n\nError:\n").append(errorMessage);
		}

		detailArea.setText(sb.toString());
		detailArea.setCaretPosition(0);
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
