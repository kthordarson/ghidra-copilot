package ghidracopilot.ui;

import java.awt.Color;

import javax.swing.UIManager;

/**
 * Centralized color palette for Copilot chat UI components.
 * Derives text colours from the current L&amp;F so they work in both
 * light and dark Ghidra themes.
 */
public final class CopilotTheme {

	private CopilotTheme() {}

	// --- Foreground helper (respects dark mode) ---
	private static Color foreground() {
		Color fg = UIManager.getColor("Label.foreground");
		return fg != null ? fg : new Color(0xD4D4D4);
	}

	public static Color background() {
		Color bg = UIManager.getColor("Panel.background");
		if (bg == null) return new Color(0x1E1E1E);
		return bg;
	}

	/** Darker background for the chat transcript area. */
	public static Color chatBackground() {
		if (isDark()) {
			Color bg = background();
			// Darken the panel background for the chat area
			return new Color(
				Math.max(bg.getRed() - 30, 0),
				Math.max(bg.getGreen() - 30, 0),
				Math.max(bg.getBlue() - 30, 0));
		}
		return new Color(0xFFFFFF);
	}

	private static boolean isDark() {
		Color bg = background();
		// luminance approximation
		return (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
	}

	// --- Assistant ---
	public static Color assistantBackground() {
		return isDark() ? lookup("Copilot.assistant.background", 0x2D2D2D)
		                : lookup("Copilot.assistant.background", 0xE5E9F0);
	}

	public static Color assistantBorder() {
		return isDark() ? lookup("Copilot.assistant.border", 0x404040)
		                : lookup("Copilot.assistant.border", 0xCBD2DC);
	}

	public static Color assistantText() {
		return lookupOrDefault("Copilot.assistant.text", foreground());
	}

	// --- User message ---
	public static Color userText() {
		return lookupOrDefault("Copilot.user.text", foreground());
	}

	// --- Role indicators ---
	public static Color chevronColor() {
		return lookup("Copilot.chevron", 0x3B82F6);
	}

	public static Color copilotDotColor() {
		return lookup("Copilot.copilotDot", 0x8B5CF6);
	}

	// --- System message ---
	public static Color systemText() {
		Color fg = foreground();
		// Mute the foreground slightly for system messages
		int r = fg.getRed(), g = fg.getGreen(), b = fg.getBlue();
		if (isDark()) {
			return lookupOrDefault("Copilot.system.text",
				new Color(r * 2 / 3 + 60, g * 2 / 3 + 60, b * 2 / 3 + 60));
		}
		return lookupOrDefault("Copilot.system.text",
			new Color(r / 2 + 40, g / 2 + 40, b / 2 + 40));
	}

	// --- Tool call ---
	public static Color toolBackground() {
		return isDark() ? lookup("Copilot.tool.background", 0x252525)
		                : lookup("Copilot.tool.background", 0xE0EEF9);
	}

	public static Color toolBorder() {
		return isDark() ? lookup("Copilot.tool.border", 0x3A3A3A)
		                : lookup("Copilot.tool.border", 0x9FC2E5);
	}

	public static Color toolText() {
		return lookupOrDefault("Copilot.tool.text", foreground());
	}

	public static Color toolDetailBackground() {
		return isDark() ? lookup("Copilot.tool.detail.background", 0x1E1E1E)
		                : lookup("Copilot.tool.detail.background", 0xF6FAFF);
	}

	public static Color toolDetailBorder() {
		return isDark() ? lookup("Copilot.tool.detail.border", 0x333333)
		                : lookup("Copilot.tool.detail.border", 0xD2E2F3);
	}

	// --- Tool call state colors (these are intentionally vivid in both modes) ---
	public static Color stateInvoked() {
		return lookup("Copilot.state.invoked", 0x6B7280);
	}

	public static Color stateInProgress() {
		return lookup("Copilot.state.inProgress", 0xD97706);
	}

	public static Color stateCompleted() {
		return lookup("Copilot.state.completed", 0x16A34A);
	}

	public static Color stateFailed() {
		return lookup("Copilot.state.failed", 0xDC2626);
	}

	// --- Code block (MarkdownRenderer) ---
	public static Color codeBackground() {
		return isDark() ? lookup("Copilot.code.background", 0x1E1E1E)
		                : lookup("Copilot.code.background", 0xf7f9fb);
	}

	public static Color codeBorder() {
		return isDark() ? lookup("Copilot.code.border", 0x3A3A3A)
		                : lookup("Copilot.code.border", 0xd0d7de);
	}

	// --- Thinking indicator ---
	public static Color thinkingText() {
		Color sys = systemText();
		// Even more muted than system text
		int r = sys.getRed(), g = sys.getGreen(), b = sys.getBlue();
		if (isDark()) {
			return new Color(r * 3 / 4, g * 3 / 4, b * 3 / 4);
		}
		return new Color(r + (255 - r) / 3, g + (255 - g) / 3, b + (255 - b) / 3);
	}

	// --- Helpers ---

	private static Color lookup(String key, int defaultRgb) {
		Color found = UIManager.getColor(key);
		return found != null ? found : new Color(defaultRgb);
	}

	private static Color lookupOrDefault(String key, Color defaultColor) {
		Color found = UIManager.getColor(key);
		return found != null ? found : defaultColor;
	}
}
