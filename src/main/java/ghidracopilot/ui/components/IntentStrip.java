package ghidracopilot.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.CopilotTheme;

/**
 * Slim status strip between the transcript and input area.
 * Shows what the model is currently doing: thinking, executing tools,
 * or the latest reported intent. Hidden when idle.
 */
public class IntentStrip extends JPanel {

	private static final String ICON_DOT = "\u25CF";
	private static final float[] THINKING_PULSE_LEVELS = { 0.45f, 0.7f, 1.0f, 0.7f };

	private final JLabel iconLabel;
	private final JLabel textLabel;
	private final Timer animationTimer;
	private final float baseIconSize;
	private int iconFrame;
	private String currentText;
	private boolean active;

	public IntentStrip() {
		super(new BorderLayout(6, 0));
		setOpaque(true);
		setBackground(CopilotTheme.chatBackground());
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, CopilotTheme.codeBorder()),
			new EmptyBorder(4, 10, 4, 10)));

		Color accentColor = CopilotTheme.stateInProgress();

		iconLabel = new JLabel(ICON_DOT);
		iconLabel.setForeground(accentColor);
		baseIconSize = iconLabel.getFont().getSize2D();
		iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, baseIconSize));
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setVerticalAlignment(SwingConstants.TOP);
		iconLabel.setBorder(new EmptyBorder(new Insets(1, 0, 0, 0)));
		int iconSlot = Math.round(baseIconSize * 1.8f);
		iconLabel.setPreferredSize(new Dimension(iconSlot, iconSlot));
		iconLabel.setMinimumSize(new Dimension(iconSlot, iconSlot));
		add(iconLabel, BorderLayout.WEST);

		textLabel = new JLabel();
		textLabel.setForeground(accentColor);
		textLabel.setFont(textLabel.getFont().deriveFont(Font.PLAIN));
		add(textLabel, BorderLayout.CENTER);

		animationTimer = new Timer(280, e -> animateIcon());
		animationTimer.setRepeats(true);

		setIdle();
	}

	/** Show "Thinking" with an animated activity dot. */
	public void setThinking() {
		currentText = "Thinking";
		active = true;
		iconFrame = 0;
		iconLabel.setText(ICON_DOT);
		iconLabel.setForeground(CopilotTheme.stateInProgress());
		textLabel.setForeground(CopilotTheme.stateInProgress());
		applyThinkingPulse();
		textLabel.setText(currentText);
		animationTimer.start();
		setVisible(true);
		revalidate();
	}

	/** Show a specific intent string. */
	public void setIntent(String intent) {
		if (intent == null || intent.isBlank()) {
			return;
		}
		ghidra.util.Msg.debug(this, "[IntentStrip] setIntent: '" + intent.trim() + "'");
		currentText = intent.trim();
		active = true;
		iconFrame = 0;
		iconLabel.setText(ICON_DOT);
		iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, baseIconSize));
		iconLabel.setForeground(CopilotTheme.copilotDotColor());
		textLabel.setForeground(CopilotTheme.copilotDotColor());
		textLabel.setText(currentText);
		animationTimer.start();
		setVisible(true);
		revalidate();
	}

	/** Hide the strip — model is idle. */
	public void setIdle() {
		active = false;
		currentText = null;
		animationTimer.stop();
		iconFrame = 0;
		iconLabel.setText(ICON_DOT);
		iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, baseIconSize));
		textLabel.setText("");
		setVisible(false);
		revalidate();
	}

	@Override
	public Dimension getPreferredSize() {
		if (!active) {
			return new Dimension(0, 0);
		}
		return super.getPreferredSize();
	}

	@Override
	public Dimension getMinimumSize() {
		if (!active) {
			return new Dimension(0, 0);
		}
		return super.getMinimumSize();
	}

	private void animateIcon() {
		if (!active || currentText == null) {
			return;
		}
		if (!"Thinking".equals(currentText)) {
			return;
		}
		iconFrame = (iconFrame + 1) % THINKING_PULSE_LEVELS.length;
		applyThinkingPulse();
	}

	private void applyThinkingPulse() {
		float level = THINKING_PULSE_LEVELS[iconFrame];
		Color base = CopilotTheme.stateInProgress();
		int alpha = Math.max(48, Math.min(255, Math.round(level * 255f)));
		iconLabel.setForeground(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
	}
}
