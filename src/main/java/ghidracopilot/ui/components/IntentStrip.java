package ghidracopilot.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.CopilotTheme;

/**
 * Slim status strip between the transcript and input area.
 * Shows what the model is currently doing: thinking, executing tools,
 * or the latest reported intent. Hidden when idle.
 */
public class IntentStrip extends JPanel {

	private static final String ICON_THINKING = "\u27F3";
	private static final String[] DOTS = { "", ".", "..", "..." };

	private final JLabel iconLabel;
	private final JLabel textLabel;
	private final Timer animationTimer;
	private int dotFrame;
	private String currentText;
	private boolean active;

	public IntentStrip() {
		super(new BorderLayout(6, 0));
		setOpaque(false);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, CopilotTheme.codeBorder()),
			new EmptyBorder(4, 10, 4, 10)));

		Color accentColor = CopilotTheme.stateInProgress();

		iconLabel = new JLabel(ICON_THINKING);
		iconLabel.setForeground(accentColor);
		iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD));
		add(iconLabel, BorderLayout.WEST);

		textLabel = new JLabel();
		textLabel.setForeground(accentColor);
		textLabel.setFont(textLabel.getFont().deriveFont(Font.ITALIC));
		add(textLabel, BorderLayout.CENTER);

		animationTimer = new Timer(400, e -> animateDots());
		animationTimer.setRepeats(true);

		setIdle();
	}

	/** Show "Thinking…" with animated dots. */
	public void setThinking() {
		currentText = "Thinking";
		active = true;
		dotFrame = 0;
		textLabel.setText(currentText);
		iconLabel.setForeground(CopilotTheme.stateInProgress());
		textLabel.setForeground(CopilotTheme.stateInProgress());
		animationTimer.start();
		setVisible(true);
		revalidate();
	}

	/** Show a specific intent string. */
	public void setIntent(String intent) {
		if (intent == null || intent.isBlank()) {
			return;
		}
		currentText = intent.trim();
		active = true;
		animationTimer.stop();
		iconLabel.setForeground(CopilotTheme.copilotDotColor());
		textLabel.setForeground(CopilotTheme.copilotDotColor());
		textLabel.setText(currentText);
		setVisible(true);
		revalidate();
	}

	/** Hide the strip — model is idle. */
	public void setIdle() {
		active = false;
		currentText = null;
		animationTimer.stop();
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

	private void animateDots() {
		if (!active || currentText == null) {
			return;
		}
		dotFrame = (dotFrame + 1) % DOTS.length;
		textLabel.setText(currentText + DOTS[dotFrame]);
	}
}
