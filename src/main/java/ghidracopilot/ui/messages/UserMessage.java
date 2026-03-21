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
 * User message rendered with a chevron prefix.
 */
public class UserMessage extends AbstractChatMessage {

	public UserMessage(String markdown) {
		super(markdown, CopilotTheme.userText());

		setLayout(new BorderLayout(6, 0));

		JLabel chevron = new JLabel("❯");
		chevron.setForeground(CopilotTheme.chevronColor());
		chevron.setFont(chevron.getFont().deriveFont(Font.BOLD));
		chevron.setVerticalAlignment(SwingConstants.TOP);
		chevron.setBorder(new EmptyBorder(3, 0, 0, 0));

		add(chevron, BorderLayout.WEST);
		add(getContentPanel(), BorderLayout.CENTER);
	}
}
