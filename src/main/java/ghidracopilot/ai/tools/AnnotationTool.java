package ghidracopilot.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.symbol.SymbolUtilities;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

/**
 * Tools for annotating the current program with comments, labels, and bookmarks.
 */
final class AnnotationTool {

	private final CopilotToolContext context;

	AnnotationTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "set_comment",
		description = "Apply or clear a comment at the specified address.")
	ToolResult setComment(
		@ToolParam(description = "Hexadecimal address where the comment should be applied.", required = true)
		String addressText,
		@ToolParam(description = "Comment text to apply. Leave blank or null to clear the comment.")
		String comment,
		@ToolParam(description = "Comment type: plate, pre, post, eol, repeat. Defaults to eol.")
		String commentType) {
		return context.withCurrentProgram(program -> doSetComment(program, addressText, comment, commentType));
	}

	@Tool(name = "rename_label",
		description = "Create or rename a label at the specified address.")
	ToolResult renameLabel(
		@ToolParam(description = "Hexadecimal address for the label.", required = true)
		String addressText,
		@ToolParam(description = "New label name.", required = true)
		String newName,
		@ToolParam(description = "Whether to make the label primary (default true).")
		Boolean makePrimary) {
		return context.withCurrentProgram(program -> doRenameLabel(program, addressText, newName, makePrimary));
	}

	@Tool(name = "flag_address",
		description = "Create a bookmark to flag a suspicious or noteworthy address.")
	ToolResult flagAddress(
		@ToolParam(description = "Hexadecimal address to flag.", required = true)
		String addressText,
		@ToolParam(description = "Bookmark category, e.g., 'Copilot'. Defaults to 'Copilot'.")
		String category,
		@ToolParam(description = "Brief description of why the address is being flagged.", required = true)
		String note) {
		return context.withCurrentProgram(program -> doFlagAddress(program, addressText, category, note));
	}

	private ToolResult doSetComment(Program program, String addressText, String comment, String commentType) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		Listing listing = program.getListing();
		CodeUnit codeUnit = listing.getCodeUnitAt(address);
		if (codeUnit == null) {
			return ToolResult.error("No code unit found at " + context.formatAddress(address));
		}

		int type = resolveCommentType(commentType);

		boolean commit = false;
		int tx = program.startTransaction("Copilot Set Comment");
		try {
			if (comment == null || comment.isBlank()) {
				codeUnit.setComment(type, null);
				commit = true;
				return ToolResult.success("Cleared comment at " + context.formatAddress(address));
			}
			String normalized = comment.trim();
			codeUnit.setComment(type, normalized);
			commit = true;
			return ToolResult.success("Applied comment at " + context.formatAddress(address), normalized);
		}
		catch (InvalidInputException ex) {
			return ToolResult.error("Invalid comment text: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to set comment: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private ToolResult doRenameLabel(Program program, String addressText, String newName, Boolean makePrimary) {
		if (newName == null || newName.isBlank()) {
			return ToolResult.error("New label name must not be blank.");
		}
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		String normalized = SymbolUtilities.replaceInvalidChars(newName.trim(), true);
		SymbolTable symbolTable = program.getSymbolTable();

		boolean commit = false;
		int tx = program.startTransaction("Copilot Rename Label");
		try {
			Symbol existing = symbolTable.getPrimarySymbol(address);
			Symbol symbol = existing;
			if (existing == null) {
				symbol = symbolTable.createLabel(address, normalized, context.userSourceType());
			}
			else {
				existing.setName(normalized, context.userSourceType());
			}
			if (symbol != null && (makePrimary == null || makePrimary.booleanValue())) {
				symbolTable.setPrimarySymbol(symbol);
			}
			commit = true;
			return ToolResult.success("Label set to " + normalized + " at " + context.formatAddress(address));
		}
		catch (DuplicateNameException ex) {
			return ToolResult.error("Duplicate label name: " + ex.getMessage());
		}
		catch (InvalidInputException ex) {
			return ToolResult.error("Invalid label name: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to rename label: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private ToolResult doFlagAddress(Program program, String addressText, String category, String note) {
		if (note == null || note.isBlank()) {
			return ToolResult.error("A bookmark note/description is required.");
		}
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		String normalizedCategory = (category == null || category.isBlank()) ? "Copilot" : category.trim();

		boolean commit = false;
		int tx = program.startTransaction("Copilot Flag Address");
		try {
			program.getBookmarkManager().setBookmark(address, "Info", normalizedCategory, note.trim());
			commit = true;
			return ToolResult.success(
				"Bookmark added at " + context.formatAddress(address),
				"Category: " + normalizedCategory + ", Note: " + note.trim());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to create bookmark: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private int resolveCommentType(String commentType) {
		if (commentType == null || commentType.isBlank()) {
			return CodeUnit.EOL_COMMENT;
		}
		String normalized = commentType.trim().toLowerCase();
		return switch (normalized) {
			case "plate" -> CodeUnit.PLATE_COMMENT;
			case "pre" -> CodeUnit.PRE_COMMENT;
			case "post" -> CodeUnit.POST_COMMENT;
			case "repeat" -> CodeUnit.REPEATABLE_COMMENT;
			case "eol", "end", "end-of-line" -> CodeUnit.EOL_COMMENT;
			default -> CodeUnit.EOL_COMMENT;
		};
	}
}
