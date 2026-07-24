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

import java.awt.BorderLayout;
import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.CopilotTheme;

/**
 * Lightweight status message with a small dot prefix.
 */
public class SystemMessage extends AbstractChatMessage {

	public SystemMessage(String markdown) {
		super(markdown, CopilotTheme.systemText());

		setLayout(new BorderLayout(6, 0));

		JLabel dot = new JLabel("\u25CF");
		dot.setForeground(CopilotTheme.systemText());
		dot.setFont(dot.getFont().deriveFont(Font.BOLD, dot.getFont().getSize2D()));
		dot.setVerticalAlignment(SwingConstants.TOP);
		dot.setBorder(new EmptyBorder(3, 0, 0, 0));

		add(dot, BorderLayout.WEST);
		add(getContentPanel(), BorderLayout.CENTER);

		MarkdownRenderer.applyFontStyle(getContentComponent(), Font.ITALIC);
	}
}
