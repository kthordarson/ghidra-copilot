package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;

/**
 * Tool that summarizes metadata for the current program.
 */
final class ProgramMetadataTool {

	private final CopilotToolContext context;

	ProgramMetadataTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "dump_program_metadata",
		description = "Return key metadata about the currently active program (image base, entry points, sections, etc.).")
	ToolResult dumpProgramMetadata() {
		return context.withCurrentProgram(this::doDumpProgramMetadata);
	}

	private ToolResult doDumpProgramMetadata(Program program) {
		try {
			List<String> lines = new ArrayList<>();
			lines.add("Program: " + program.getName());
			lines.add("Language: " + program.getLanguage().getLanguageID().getIdAsString());
			lines.add("Compiler: " + program.getCompilerSpec().getCompilerSpecID().getIdAsString());

			Address imageBase = program.getImageBase();
			lines.add("Image Base: " + context.formatAddress(imageBase));

			Address min = program.getMinAddress();
			Address max = program.getMaxAddress();
			lines.add("Address Range: " + context.formatAddress(min) + " - " + context.formatAddress(max));

			lines.add("Functions: " + program.getFunctionManager().getFunctionCount());

			appendEntryPoints(program, lines);
			appendMemoryBlocks(program, lines);
			appendExternalSymbols(program, lines);

			return ToolResult.success("Program metadata retrieved.", String.join("\n", lines));
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to gather program metadata: " + ex.getMessage());
		}
	}

	private void appendEntryPoints(Program program, List<String> lines) {
		SymbolTable symbolTable = program.getSymbolTable();
		AddressIterator iterator = symbolTable.getExternalEntryPointIterator();
		if (iterator == null || !iterator.hasNext()) {
			lines.add("Entry Points: <none reported>");
			return;
		}
		List<String> entries = new ArrayList<>();
		int count = 0;
		boolean truncated = false;
		FunctionManager functionManager = program.getFunctionManager();
		while (iterator.hasNext()) {
			if (count >= 10) {
				truncated = true;
				break;
			}
			Address address = iterator.next();
			Function function = functionManager.getFunctionAt(address);
			String name = function != null ? function.getName() : null;
			if (name == null) {
				Symbol symbol = symbolTable.getPrimarySymbol(address);
				name = symbol != null ? symbol.getName(true) : "<no symbol>";
			}
			entries.add(name + " @ " + context.formatAddress(address));
			count++;
		}
		if (entries.isEmpty()) {
			lines.add("Entry Points: <none reported>");
			return;
		}
		if (truncated) {
			entries.add("... additional entry points omitted ...");
		}
		lines.add("Entry Points:");
		for (String entry : entries) {
			lines.add("  " + entry);
		}
	}

	private void appendMemoryBlocks(Program program, List<String> lines) {
		lines.add("Sections:");
		MemoryBlock[] blocks = program.getMemory().getBlocks();
		if (blocks == null || blocks.length == 0) {
			lines.add("  <none>");
			return;
		}
		for (MemoryBlock block : blocks) {
			StringBuilder builder = new StringBuilder();
			builder.append("  ")
				.append(block.getName())
				.append(" : ")
				.append(context.formatAddress(block.getStart()))
				.append(" - ")
				.append(context.formatAddress(block.getEnd()))
				.append(" (");
			builder.append(block.isExecute() ? "X" : "-");
			builder.append(block.isRead() ? "R" : "-");
			builder.append(block.isWrite() ? "W" : "-");
			builder.append(")");
			lines.add(builder.toString());
		}
	}

	private void appendExternalSymbols(Program program, List<String> lines) {
		SymbolTable symbolTable = program.getSymbolTable();
		SymbolIterator iterator = symbolTable.getExternalSymbols();
		if (iterator == null || !iterator.hasNext()) {
			lines.add("Imports: <none>");
			return;
		}
		List<String> imports = new ArrayList<>();
		while (iterator.hasNext() && imports.size() < 50) {
			Symbol symbol = iterator.next();
			imports.add(symbol.getName(true));
		}
		if (iterator.hasNext()) {
			imports.add("... additional imports omitted ...");
		}
		lines.add("Imports (" + imports.size() + "):");
		for (String entry : imports) {
			lines.add("  " + entry);
		}
	}
}
