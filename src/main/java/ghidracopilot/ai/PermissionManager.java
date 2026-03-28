package ghidracopilot.ai;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Manages per-session permission state for mutation tools.
 * <p>
 * When a mutation tool is invoked, the manager checks whether the user
 * has already approved it (or all mutations). If not, it delegates to
 * a {@link PermissionPrompter} callback to ask the user.
 * <p>
 * Approval levels:
 * <ul>
 *   <li><b>Once</b> — allow this single invocation</li>
 *   <li><b>Tool</b> — allow this tool name for the rest of the session</li>
 *   <li><b>All</b> — allow all mutation tools for the rest of the session</li>
 *   <li><b>Deny</b> — reject this invocation</li>
 * </ul>
 */
public final class PermissionManager {

	/** The user's decision on a permission prompt. */
	public enum Decision {
		ALLOW_ONCE,
		ALLOW_TOOL,
		ALLOW_ALL,
		DENY
	}

	/** Request payload shown to the user. */
	public record PermissionRequest(String toolName, String description) {}

	/** Callback that shows a permission prompt and returns the user's decision. */
	@FunctionalInterface
	public interface PermissionPrompter {
		CompletableFuture<Decision> prompt(PermissionRequest request);
	}

	private volatile boolean allAllowed;
	private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();
	private volatile PermissionPrompter prompter;

	public void setPrompter(PermissionPrompter prompter) {
		this.prompter = prompter;
	}

	/** Allow all mutation tools for this session. */
	public void allowAll() {
		allAllowed = true;
	}

	/** Allow a specific tool by name for this session. */
	public void allowTool(String toolName) {
		if (toolName != null) {
			allowedTools.add(toolName);
		}
	}

	/** Check whether a tool is pre-approved (doesn't prompt). */
	public boolean isApproved(String toolName) {
		return allAllowed || allowedTools.contains(toolName);
	}

	/** Reset all approvals (e.g. on new chat). */
	public void reset() {
		allAllowed = false;
		allowedTools.clear();
	}

	/**
	 * Check permission for a mutation tool. If already approved, returns true
	 * immediately. Otherwise prompts the user and blocks until they respond.
	 *
	 * @param toolName    the tool's registered name
	 * @param description human-readable summary of what the tool will do
	 * @return true if approved, false if denied
	 */
	public boolean checkPermission(String toolName, String description) {
		if (allAllowed || allowedTools.contains(toolName)) {
			return true;
		}

		PermissionPrompter p = prompter;
		if (p == null) {
			// No prompter wired — deny by default
			return false;
		}

		try {
			Decision decision = p.prompt(new PermissionRequest(toolName, description))
				.get();

			switch (decision) {
				case ALLOW_ONCE -> { return true; }
				case ALLOW_TOOL -> { allowedTools.add(toolName); return true; }
				case ALLOW_ALL -> { allAllowed = true; return true; }
				case DENY -> { return false; }
			}
		}
		catch (Exception ex) {
			// Interrupted or cancelled — deny
			Thread.currentThread().interrupt();
		}
		return false;
	}
}
