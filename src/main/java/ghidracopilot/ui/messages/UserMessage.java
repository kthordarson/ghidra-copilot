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

/**
 * Chat bubble rendered for user-authored messages.
 */
public class UserMessage extends AbstractChatMessage {

	private static final Color BACKGROUND = new Color(0x2B8AE2);
	private static final Color BORDER = new Color(0x1F5B97);
	private static final Color TEXT = Color.WHITE;

	public UserMessage(String markdown) {
		super(markdown, ChatAlignment.RIGHT, BACKGROUND, BORDER, TEXT);
	}
}
