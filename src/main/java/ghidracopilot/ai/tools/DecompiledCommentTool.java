package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.decompiler.ClangNode;
import ghidra.app.decompiler.ClangStatement;
import ghidra.app.decompiler.ClangToken;
import ghidra.app.decompiler.ClangTokenGroup;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Seqnum;
import ghidra.program.model.listing.CommentType;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Tools that let the LLM inspect the decompiler output line-by-line and attach comments to the
 * backing instructions.
 */
final class DecompiledCommentTool {

	private static final int DECOMPILE_TIMEOUT_SECONDS = 30;

	private final CopilotToolContext context;

	DecompiledCommentTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "list_decompiled_lines",
		description = "Return the formatted decompiled lines for the function containing the address, "
			+ "including line numbers and (optionally) backing addresses.")
	ToolResult listDecompiledLines(
		@ToolParam(description = "Address within the target function (entry point or any instruction).",
			required = true)
		String addressText,
		@ToolParam(description = "First line number to include (1-based). Defaults to 1.")
		Integer startLine,
		@ToolParam(description = "Last line number to include (inclusive). Defaults to the end of the function.")
		Integer endLine,
		@ToolParam(description = "Include the resolved addresses for each line.")
		Boolean includeAddresses) {
		boolean emitAddresses = includeAddresses != null && includeAddresses.booleanValue();
		return context.withCurrentProgram(program -> withDecompiledView(program, addressText, view -> {
			int from = (startLine == null || startLine.intValue() < 1) ? 1 : startLine.intValue();
			int to = (endLine == null || endLine.intValue() < from)
				? view.maxLine()
				: Math.min(endLine.intValue(), view.maxLine());
			List<LineInfo> window = view.linesBetween(from, to);
			if (window.isEmpty()) {
				return ToolResult.error("No decompiled lines available in the specified range.");
			}
			StringBuilder data = new StringBuilder();
			for (LineInfo line : window) {
				data.append(String.format(Locale.ROOT, "%4d | %s%n", line.lineNumber(), line.text()));
				if (emitAddresses && !line.addresses().isEmpty()) {
					data.append("      addresses: ");
					data.append(line.addresses().stream().map(context::formatAddress).reduce((a, b) -> a + ", " + b)
						.orElse(""));
					data.append(System.lineSeparator());
				}
			}
			String message = "Decompiled lines " + from + "-" + to + " for function " + view.function().getName();
			String payload = data.toString();
			return ToolResult.success(message, payload.stripTrailing());
		}));
	}

	@Tool(name = "describe_decompiled_line",
		description = "Inspect a specific decompiled line to understand which instructions it maps to.")
	ToolResult describeDecompiledLine(
		@ToolParam(description = "Address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "1-based decompiled line number to describe.")
		Integer lineNumber,
		@ToolParam(description = "Text snippet to locate when the exact line number is unknown.")
		String containingText) {
		return context.withCurrentProgram(program -> withDecompiledView(program, addressText, view -> {
			Optional<LineInfo> line;
			try {
				line = resolveLine(view, lineNumber, containingText);
			}
			catch (IllegalArgumentException ex) {
				return ToolResult.error(ex.getMessage());
			}
			if (line.isEmpty()) {
				return ToolResult.error("Unable to locate the requested decompiled line.");
			}
			LineInfo info = line.get();
			StringBuilder data = new StringBuilder();
			data.append("Line ").append(info.lineNumber()).append(": ").append(info.text()).append(System.lineSeparator());
			if (info.anchor() != null) {
				data.append("Primary address: ").append(context.formatAddress(info.anchor())).append(System.lineSeparator());
			}
			if (!info.addresses().isEmpty()) {
				data.append("All addresses: ");
				data.append(info.addresses().stream().map(context::formatAddress).reduce((a, b) -> a + ", " + b).orElse(""));
				data.append(System.lineSeparator());
			}
			if (!info.tokens().isEmpty()) {
				data.append("Tokens: ").append(String.join(" ", info.tokens())).append(System.lineSeparator());
			}
			return ToolResult.success("Located decompiled line " + info.lineNumber(),
				data.toString().stripTrailing());
		}));
	}

	@Tool(name = "comment_decompiled_line",
		description = "Attach a comment to the instruction that backs a specific decompiled source line.")
	ToolResult commentDecompiledLine(
		@ToolParam(description = "Address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "1-based decompiled line number to annotate.")
		Integer lineNumber,
		@ToolParam(description = "Snippet that uniquely identifies the target line (case-insensitive).")
		String containingText,
		@ToolParam(description = "Comment text to apply.", required = true)
		String comment,
		@ToolParam(description = "Comment type: plate, pre, post, eol, repeat. Defaults to eol.")
		String commentType) {
		if (comment == null || comment.isBlank()) {
			return ToolResult.error("Comment text must not be empty.");
		}
		return context.withCurrentProgram(program -> withDecompiledView(program, addressText, view -> {
			Optional<LineInfo> line;
			try {
				line = resolveLine(view, lineNumber, containingText);
			}
			catch (IllegalArgumentException ex) {
				return ToolResult.error(ex.getMessage());
			}
			if (line.isEmpty()) {
				return ToolResult.error("Unable to locate the requested decompiled line for commenting.");
			}
			LineInfo info = line.get();
			Address anchor = info.anchor();
			if (!isValidAddress(anchor)) {
				return ToolResult.error("Selected line does not resolve to a specific instruction address.");
			}
			CommentType type = resolveCommentType(commentType);
			String normalized = comment.trim();
			Listing listing = program.getListing();

			boolean commit = false;
			int tx = program.startTransaction("Copilot Comment Decompiled Line");
			try {
				listing.setComment(anchor, type, normalized);
				commit = true;
			}
			catch (Exception ex) {
				return ToolResult.error("Failed to set comment: " + ex.getMessage());
			}
			finally {
				program.endTransaction(tx, commit);
			}
			String message = "Comment applied to line " + info.lineNumber() + " at " + context.formatAddress(anchor);
			return ToolResult.success(message, normalized);
		}));
	}

	private ToolResult withDecompiledView(Program program, String addressText, Function<DecompiledView, ToolResult> action) {
		Address address;
		try {
			address = context.parseAddress(program, addressText);
		}
		catch (IllegalArgumentException ex) {
			return ToolResult.error(ex.getMessage());
		}
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}
		FunctionManager functions = program.getFunctionManager();
		Function function = functions.getFunctionContaining(address);
		if (function == null) {
			return ToolResult.error("No function found containing address " + context.formatAddress(address));
		}
		try {
			DecompiledView view = DecompiledView.from(program, function);
			return action.apply(view);
		}
		catch (Exception ex) {
			return ToolResult.error("Decompiler error: " + ex.getMessage());
		}
	}

	private Optional<LineInfo> resolveLine(DecompiledView view, Integer lineNumber, String containingText) {
		if (lineNumber != null && lineNumber.intValue() > 0) {
			LineInfo info = view.line(lineNumber.intValue());
			if (info != null) {
				return Optional.of(info);
			}
		}
		if (containingText != null && !containingText.isBlank()) {
			String needle = containingText.trim().toLowerCase(Locale.ROOT);
			List<LineInfo> matches = new ArrayList<>();
			for (LineInfo info : view.lines()) {
				if (info.text().toLowerCase(Locale.ROOT).contains(needle)) {
					matches.add(info);
				}
			}
			if (matches.size() == 1) {
				return Optional.of(matches.get(0));
			}
			if (matches.isEmpty()) {
				return Optional.empty();
			}
			throw new IllegalArgumentException(
				"Multiple decompiled lines contain the provided snippet; please specify the line number.");
		}
		return Optional.empty();
	}

	private CommentType resolveCommentType(String commentType) {
		if (commentType == null || commentType.isBlank()) {
			return CommentType.EOL;
		}
		return switch (commentType.trim().toLowerCase(Locale.ROOT)) {
			case "plate" -> CommentType.PLATE;
			case "pre" -> CommentType.PRE;
			case "post" -> CommentType.POST;
			case "repeat", "repeatable" -> CommentType.REPEATABLE;
			case "eol", "end", "inline", "line" -> CommentType.EOL;
			default -> CommentType.EOL;
		};
	}

	private static boolean isValidAddress(Address address) {
		return address != null && !Address.NO_ADDRESS.equals(address);
	}

	private record LineInfo(int lineNumber, String text, Address anchor, List<Address> addresses, List<String> tokens) {
	}

	private record DecompiledView(Function function, List<LineInfo> ordered, Map<Integer, LineInfo> byNumber) {

		static DecompiledView from(Program program, Function function) {
			DecompInterface iface = new DecompInterface();
			if (!iface.openProgram(program)) {
				throw new IllegalStateException("Decompiler refused program: " + iface.getLastMessage());
			}
			try {
				DecompileResults results = iface.decompileFunction(function, DECOMPILE_TIMEOUT_SECONDS,
					TaskMonitorAdapter.DUMMY_MONITOR);
				if (!results.decompileCompleted()) {
					throw new IllegalStateException(results.getErrorMessage());
				}
				ClangTokenGroup markup = results.getCCodeMarkup();
				String c = results.getDecompiledFunction().getC();
				List<String> rawLines = splitLines(c);
				Map<Integer, LineAccumulator> accumulators = new LinkedHashMap<>();

				if (markup != null) {
					for (ClangToken token : iterable(markup.tokenIterator(true))) {
						int lineNumber = token.getLineNumber();
						if (lineNumber < 0) {
							continue;
						}
						LineAccumulator acc = accumulators.computeIfAbsent(lineNumber, LineAccumulator::new);
						acc.addToken(token);
					}
				}

				int totalLines = Math.max(rawLines.size(), accumulators.keySet().stream().max(Integer::compareTo).orElse(0));
				List<LineInfo> ordered = new ArrayList<>(totalLines);
				Map<Integer, LineInfo> byNumber = new LinkedHashMap<>();

				for (int index = 1; index <= totalLines; index++) {
					LineAccumulator acc = accumulators.computeIfAbsent(index, LineAccumulator::new);
					String text = index <= rawLines.size() ? rawLines.get(index - 1) : "";
					acc.setText(text);
					LineInfo info = acc.toLineInfo();
					ordered.add(info);
					byNumber.put(info.lineNumber(), info);
				}

				ordered.sort(Comparator.comparingInt(LineInfo::lineNumber));
				return new DecompiledView(function, Collections.unmodifiableList(ordered),
					Collections.unmodifiableMap(byNumber));
			}
			finally {
				iface.dispose();
			}
		}

		private static List<String> splitLines(String c) {
			if (c == null || c.isBlank()) {
				return List.of();
			}
			String[] parts = c.split("\\R", -1);
			List<String> lines = new ArrayList<>(parts.length);
			Collections.addAll(lines, parts);
			while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
				lines.remove(lines.size() - 1);
			}
			return lines;
		}

		private static Iterable<ClangToken> iterable(java.util.Iterator<ClangToken> iterator) {
			return () -> iterator;
		}

		int maxLine() {
			return ordered.isEmpty() ? 0 : ordered.get(ordered.size() - 1).lineNumber();
		}

		List<LineInfo> linesBetween(int startInclusive, int endInclusive) {
			List<LineInfo> slice = new ArrayList<>();
			for (LineInfo line : ordered) {
				if (line.lineNumber() < startInclusive) {
					continue;
				}
				if (line.lineNumber() > endInclusive) {
					break;
				}
				slice.add(line);
			}
			return slice;
		}

		LineInfo line(int lineNumber) {
			return byNumber.get(lineNumber);
		}

		List<LineInfo> lines() {
			return ordered;
		}
	}

	private static final class LineAccumulator {

		private final int lineNumber;
		private String text = "";
		private final List<String> tokens = new ArrayList<>();
		private final Set<Address> addresses = new LinkedHashSet<>();
		private Address anchor;
		private boolean anchorFromStatement;

		LineAccumulator(int lineNumber) {
			this.lineNumber = lineNumber;
		}

		void setText(String text) {
			this.text = text == null ? "" : text;
		}

		void addToken(ClangToken token) {
			String tokenText = token.getText();
			if (tokenText != null && !tokenText.isEmpty()) {
				tokens.add(tokenText);
			}
			considerAddress(token.getMinAddress(), false);
			considerAddress(token.getMaxAddress(), false);
			addStatementAddress(token);
		}

		private void addStatementAddress(ClangToken token) {
			ClangNode node = token.Parent();
			while (node != null && !(node instanceof ClangStatement)) {
				node = node.Parent();
			}
			if (node instanceof ClangStatement statement) {
				considerAddress(statement.getMinAddress(), true);
				considerAddress(statement.getMaxAddress(), true);
				PcodeOp op = statement.getPcodeOp();
				if (op != null) {
					Seqnum seq = op.getSeqnum();
					if (seq != null) {
						considerAddress(seq.getTarget(), true);
					}
				}
			}
		}

		private void considerAddress(Address address, boolean fromStatement) {
			if (!isValidAddress(address)) {
				return;
			}
			addresses.add(address);
			if (anchor == null) {
				anchor = address;
				anchorFromStatement = fromStatement;
				return;
			}
			if (anchorFromStatement && !fromStatement) {
				anchor = address;
				anchorFromStatement = false;
			}
		}

		LineInfo toLineInfo() {
			List<Address> orderedAddresses = new ArrayList<>(addresses);
			return new LineInfo(lineNumber, text, anchor, Collections.unmodifiableList(orderedAddresses),
				Collections.unmodifiableList(new ArrayList<>(tokens)));
		}
	}
}
