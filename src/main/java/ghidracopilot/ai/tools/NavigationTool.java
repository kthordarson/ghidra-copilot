package ghidracopilot.ai.tools;

import java.lang.reflect.InvocationTargetException;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.services.CodeViewerService;
import ghidra.app.services.GoToService;
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
		description =
			"Navigate the current listing view to the supplied program address. Use sparingly—only when the user requests navigation or it is critical for clarity.")
	ToolResult navigateToAddress(
		@ToolParam(description = "Hexadecimal address within the current program", required = true)
		String addressText) {
		return context.withCurrentProgram(program -> doNavigate(program, addressText));
	}

	@Tool(name = "set_listing_cursor",
		description =
			"Move the listing cursor to the supplied address. Use sparingly; only when the user explicitly asks or it is essential to clarify your explanation.")
	ToolResult setListingCursor(
		@ToolParam(description = "Hexadecimal address within the current program", required = true)
		String addressText) {
		return context.withCurrentProgram(program -> doSetCursor(program, addressText));
	}

	private ToolResult doNavigate(Program program, String addressText) {
		try {
			Address address = context.parseAddress(program, addressText);
			if (address == null) {
				return ToolResult.error("Unable to parse address: " + addressText);
			}
			context.runOnSwing(() -> {
				GoToService goToService = context.goToService();
				if (goToService == null) {
					throw new IllegalStateException("GoToService is not available.");
				}
				if (!goToService.goTo(address)) {
					throw new IllegalStateException("GoToService rejected the navigation request.");
				}
			});
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

	private ToolResult doSetCursor(Program program, String addressText) {
		try {
			Address address = context.parseAddress(program, addressText);
			if (address == null) {
				return ToolResult.error("Unable to parse address: " + addressText);
			}
			context.runOnSwing(() -> {
				CodeViewerService codeViewer = context.codeViewerService();
				if (codeViewer == null) {
					throw new IllegalStateException("CodeViewerService is not available.");
				}
				ProgramLocation location = new ProgramLocation(program, address);
				if (!codeViewer.goTo(location, true)) {
					throw new IllegalStateException("CodeViewerService rejected the cursor update request.");
				}
				codeViewer.requestFocus();
			});
			return ToolResult.success("Cursor moved to " + address.toString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ToolResult.error("Cursor update interrupted: " + ex.getMessage());
		}
		catch (InvocationTargetException ex) {
			return ToolResult.error("Cursor update failed: " + ex.getTargetException().getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Cursor update failed: " + ex.getMessage());
		}
	}
}
