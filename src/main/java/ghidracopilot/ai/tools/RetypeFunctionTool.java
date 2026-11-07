package ghidracopilot.ai.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.cmd.function.FunctionRenameOption;
import ghidra.app.util.cparser.C.ParseException;
import ghidra.app.util.parser.FunctionSignatureParser;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.FunctionDefinitionDataType;
import ghidra.util.exception.InvalidInputException;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.FunctionSignature;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Tool to retype or otherwise update a function signature.
 */
final class RetypeFunctionTool implements MutationTool {

	private final CopilotToolContext context;

	RetypeFunctionTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "retype_function",
		description = "Retype a function by applying a C-style signature string. Updates return type, parameters, varargs, and optionally the name.")
	ToolResult retypeFunction(
		@ToolParam(description = "Hexadecimal entry point address of the function to update.", required = true)
		String addressText,
		@ToolParam(description = "C-style function signature (e.g., 'int foo(int a, char *b)').", required = true)
		String signatureText,
		@ToolParam(description = "If true, keep the existing calling convention instead of the one in the signature.")
		Boolean preserveCallingConvention,
		@ToolParam(description = "If true, always rename the function to the signature's name (default only renames if the current name is auto-generated).")
		Boolean forceRename) {
		return context.withCurrentProgram(
			program -> doRetype(program, addressText, signatureText, preserveCallingConvention, forceRename));
	}

	private ToolResult doRetype(Program program, String addressText, String signatureText,
			Boolean preserveCallingConvention, Boolean forceRename) {
		if (signatureText == null || signatureText.isBlank()) {
			return ToolResult.error("Signature text must not be blank.");
		}

		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionAt(address);
		if (function == null) {
			return ToolResult.error("No function exists at address " + context.formatAddress(address));
		}

		boolean preserveConvention = preserveCallingConvention != null && preserveCallingConvention;
		boolean renameAggressively = forceRename != null && forceRename;
		FunctionRenameOption renameOption =
			renameAggressively ? FunctionRenameOption.RENAME : FunctionRenameOption.RENAME_IF_DEFAULT;

		FunctionSignatureParser parser =
			new FunctionSignatureParser(program.getDataTypeManager(), context.dataTypeQueryService());
		FunctionSignature parsedSignature;
		String conventionNote = "";
		try {
			// Ghidra has no const/volatile qualifiers or 'struct' tags in its type model; strip them
			String normalized = CTypeText.normalize(signatureText);
			// The parser only recognizes conventions the compiler spec defines and otherwise folds
			// e.g. "__fastcall" into the return type. Pull it out and apply it ourselves.
			String requestedConvention = null;
			Matcher m = CONVENTION_PATTERN.matcher(normalized);
			int paren = normalized.indexOf('(');
			if (m.find() && (paren < 0 || m.start() < paren)) {
				requestedConvention = canonicalConvention(m.group(1));
				normalized = (normalized.substring(0, m.start()) + " " + normalized.substring(m.end()))
					.replaceAll("\\s+", " ").trim();
			}
			parsedSignature = parser.parse(function.getSignature(), normalized);
			if (requestedConvention != null && parsedSignature instanceof FunctionDefinitionDataType def) {
				String supported = findSupportedConvention(program, requestedConvention);
				if (supported != null) {
					def.setCallingConvention(supported);
				}
				else {
					conventionNote = " (calling convention '" + requestedConvention
						+ "' is not defined for this program's compiler spec; ignored)";
				}
			}
		}
		catch (ParseException | CancelledException | InvalidInputException ex) {
			return ToolResult.error("Unable to parse signature: " + ex.getMessage());
		}

		SourceType source = context.userSourceType();
		ApplyFunctionSignatureCmd cmd = new ApplyFunctionSignatureCmd(
			address, parsedSignature, source, preserveConvention, false, DataTypeConflictHandler.DEFAULT_HANDLER,
			renameOption);

		int tx = program.startTransaction("Copilot: Apply function signature");
		boolean success = false;
		try {
			success = cmd.applyTo(program, TaskMonitor.DUMMY);
			if (!success) {
				return ToolResult
					.error("Failed to apply signature to function at " + context.formatAddress(address));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Error applying signature: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, success);
		}

		return ToolResult.success(
			"Retyped function at " + context.formatAddress(address) + " with signature '" + signatureText.trim() + "'."
				+ conventionNote);
	}

	private static final Pattern CONVENTION_PATTERN = Pattern.compile(
		"\\b(__cdecl|_cdecl|__stdcall|_stdcall|__fastcall|_fastcall|__thiscall|__vectorcall|__pascal|__regcall"
			+ "|__clrcall|WINAPI|CALLBACK|APIENTRY|PASCAL|__syscall)\\b");

	private static String canonicalConvention(String token) {
		return switch (token) {
			case "WINAPI", "CALLBACK", "APIENTRY", "PASCAL", "_stdcall" -> "__stdcall";
			case "_cdecl" -> "__cdecl";
			case "_fastcall" -> "__fastcall";
			default -> token;
		};
	}

	private static String findSupportedConvention(Program program, String requested) {
		for (String name : program.getFunctionManager().getCallingConventionNames()) {
			if (name.equalsIgnoreCase(requested)) {
				return name;
			}
		}
		return null;
	}
}
