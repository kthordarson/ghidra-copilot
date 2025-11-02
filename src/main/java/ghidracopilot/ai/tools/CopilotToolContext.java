package ghidracopilot.ai.tools;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.function.Function;

import javax.swing.SwingUtilities;

import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.services.GoToService;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;

/**
 * Shared helpers used by Copilot tool implementations.
 */
final class CopilotToolContext {

	private final ProgramPlugin plugin;

	CopilotToolContext(ProgramPlugin plugin) {
		this.plugin = plugin;
	}

	ProgramPlugin plugin() {
		return plugin;
	}

	GoToService goToService() {
		return plugin.getTool().getService(GoToService.class);
	}

	ToolResult withCurrentProgram(Function<Program, ToolResult> action) {
		Program program = plugin.getCurrentProgram();
		if (program == null) {
			return ToolResult.error("No program is currently active.");
		}
		return action.apply(program);
	}

	Address parseAddress(Program program, String addressText) {
		if (addressText == null || addressText.isBlank()) {
			throw new IllegalArgumentException("Address is required.");
		}
		String normalized = addressText.trim();
		AddressFactory factory = program.getAddressFactory();
		Address address = factory.getAddress(normalized);
		if (address == null && normalized.startsWith("0x")) {
			address = factory.getAddress(normalized.substring(2));
		}
		if (address == null) {
			String hex = normalized.toLowerCase(Locale.ROOT).startsWith("0x")
				? normalized.substring(2)
				: normalized;
			try {
				long offset = Long.parseUnsignedLong(hex, 16);
				AddressSpace space = factory.getDefaultAddressSpace();
				if (space != null) {
					address = space.getAddress(offset);
				}
			}
			catch (NumberFormatException ignored) {
				// fall through and return null
			}
		}
		return address;
	}

	void runOnSwing(Runnable runnable) throws InterruptedException, InvocationTargetException {
		if (SwingUtilities.isEventDispatchThread()) {
			try {
				runnable.run();
			}
			catch (RuntimeException ex) {
				throw ex;
			}
			return;
		}
		SwingUtilities.invokeAndWait(runnable);
	}

	SourceType userSourceType() {
		return SourceType.USER_DEFINED;
	}

	String formatAddress(Address address) {
		if (address == null) {
			return "null";
		}
		String text = address.toString();
		if (text.startsWith("0x") || text.startsWith("0X")) {
			return text;
		}
		return "0x" + text;
	}

	int normalizeLimit(Integer requested, int defaultValue, int maxValue) {
		int base = defaultValue > 0 ? defaultValue : 1;
		int cappedMax = maxValue >= base ? maxValue : base;
		if (requested == null) {
			return base;
		}
		int normalized = Math.max(1, requested);
		return Math.min(normalized, cappedMax);
	}
}
