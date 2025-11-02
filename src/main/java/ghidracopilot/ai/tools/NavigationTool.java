package ghidracopilot.ai.tools;

import java.lang.reflect.InvocationTargetException;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;

/**
 * Tooling for navigation tasks (e.g., jumping to an address).
 */
final class NavigationTool {

	private final CopilotToolContext context;

	NavigationTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "navigate_to_address",
		description = "Navigate the current listing view to the supplied program address.")
	ToolResult navigateToAddress(
		@ToolParam(description = "Hexadecimal address within the current program", required = true)
		String addressText) {
		return context.withCurrentProgram(program -> doNavigate(program, addressText));
	}

	private ToolResult doNavigate(Program program, String addressText) {
		try {
			Address address = context.parseAddress(program, addressText);
			if (address == null) {
				return ToolResult.error("Unable to parse address: " + addressText);
			}
			ProgramLocation location = new ProgramLocation(program, address);
			context.runOnSwing(() -> context.plugin().goTo(location));
			return ToolResult.success("Navigated to " + address.toString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ToolResult.error("Navigation interrupted: " + ex.getMessage());
		}
		catch (InvocationTargetException ex) {
			return ToolResult.error("Navigation failed: " + ex.getTargetException().getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Navigation failed: " + ex.getMessage());
		}
	}
}
