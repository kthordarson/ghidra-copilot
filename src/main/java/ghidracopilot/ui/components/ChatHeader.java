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
package ghidracopilot.ui.components;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * Header section for the Copilot chat panel.
 */
public class ChatHeader extends JPanel {

	private final JLabel titleLabel;

	public ChatHeader() {
		super(new BorderLayout());
		setBorder(new EmptyBorder(8, 10, 8, 10));

		titleLabel = new JLabel("Copilot Chat", SwingConstants.LEFT);
		add(titleLabel, BorderLayout.CENTER);
	}

	public void setTitleText(String text) {
		titleLabel.setText(text);
	}
}
