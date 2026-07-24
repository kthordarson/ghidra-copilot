/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidracopilot.ui.messages;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import ghidra.program.model.listing.Program;
import ghidracopilot.ui.CopilotTheme;

/**
 * A small, muted inline widget that appears after an assistant turn
 * which performed mutations. Clicking it shows a confirmation dialog
 * and rolls back the program state by undoing N Ghidra transactions.
 *
 * <pre>
 *   ↩ 3 changes
 * </pre>
 */
public class UndoCheckpointMessage extends AbstractChatMessage {

	private static final String UNDO_ARROW = "\u21BA"; // ↺

	private final int mutationCount;
	private final List<String> mutationDescriptions;
	private final Program program;
	private final JLabel label;
	private boolean consumed;

	/**
	 * @param mutationCount        number of committed mutation transactions in this turn
	 * @param mutationDescriptions short descriptions of each mutation (tool display names)
	 * @param program              the Ghidra program to undo against
	 */
	public UndoCheckpointMessage(int mutationCount, List<String> mutationDescriptions,
			Program program) {
		super("", CopilotTheme.systemText());
		this.mutationCount = mutationCount;
		this.mutationDescriptions = mutationDescriptions;
		this.program = program;
		this.consumed = false;

		setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
		setOpaque(false);

		Color muted = CopilotTheme.thinkingText();
		String text = UNDO_ARROW + " " + mutationCount +
			(mutationCount == 1 ? " change" : " changes");

		label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.ITALIC, label.getFont().getSize2D() - 1f));
		label.setForeground(muted);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		label.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (!consumed) {
					showUndoConfirmation();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				if (!consumed) {
					label.setForeground(CopilotTheme.systemText());
				}
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (!consumed) {
					label.setForeground(muted);
				}
			}
		});

		add(label);
	}

	private void showUndoConfirmation() {
		StringBuilder details = new StringBuilder();
		details.append("Roll back ").append(mutationCount)
			.append(mutationCount == 1 ? " change" : " changes").append("?\n\n");

		int shown = 0;
		for (String desc : mutationDescriptions) {
			if (shown >= 10) {
				details.append("  ... and ").append(mutationDescriptions.size() - shown)
					.append(" more\n");
				break;
			}
			details.append("  \u2022 ").append(desc).append("\n");
			shown++;
		}

		int result = JOptionPane.showConfirmDialog(
			SwingUtilities.getWindowAncestor(this),
			details.toString(),
			"Undo Copilot Changes",
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.QUESTION_MESSAGE);

		if (result == JOptionPane.OK_OPTION) {
			performUndo();
		}
	}

	private void performUndo() {
		int undone = 0;
		try {
			for (int i = 0; i < mutationCount; i++) {
				if (!program.canUndo()) {
					break;
				}
				program.undo();
				undone++;
			}
		}
		catch (IOException ex) {
			ghidra.util.Msg.error(this, "Failed to undo Copilot changes", ex);
		}

		consumed = true;
		Color dimmed = CopilotTheme.thinkingText();
		String statusText;
		if (undone == mutationCount) {
			statusText = UNDO_ARROW + " rolled back " + undone +
				(undone == 1 ? " change" : " changes");
		}
		else if (undone > 0) {
			statusText = UNDO_ARROW + " rolled back " + undone + " of " + mutationCount;
		}
		else {
			statusText = UNDO_ARROW + " nothing to undo";
		}
		label.setText(statusText);
		label.setForeground(dimmed);
		label.setCursor(Cursor.getDefaultCursor());
	}
}
