package ghidracopilot.ui.messages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.CopilotTheme;

/**
 * Animated "thinking" indicator shown while waiting for the first
 * response delta from the model. Displays a pulsing dot animation
 * and elapsed time, and removes itself when dismissed.
 */
public class ThinkingMessage extends JPanel {

	private static final String[] FRAMES = { "·", "· ·", "· · ·" };

	private final JLabel label;
	private final Timer animationTimer;
	private final long startTime;
	private int frame;

	public ThinkingMessage() {
		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentY(Component.TOP_ALIGNMENT);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(new EmptyBorder(4, 20, 4, 14));

		Color textColor = CopilotTheme.thinkingText();
		label = new JLabel("\u00b7 Thinking " + FRAMES[0]);
		label.setForeground(textColor);
		label.setFont(label.getFont().deriveFont(java.awt.Font.ITALIC));
		add(label, BorderLayout.CENTER);

		startTime = System.currentTimeMillis();
		frame = 0;

		animationTimer = new Timer(400, e -> tick());
		animationTimer.setRepeats(true);
		animationTimer.start();
	}

	private void tick() {
		frame = (frame + 1) % FRAMES.length;
		long elapsed = (System.currentTimeMillis() - startTime) / 1000;
		String time = elapsed >= 2 ? " (" + elapsed + "s)" : "";
		label.setText("\u00b7 Thinking " + FRAMES[frame] + time);
	}

	/**
	 * Stop animation and clean up the timer.
	 */
	public void dismiss() {
		animationTimer.stop();
	}
}
