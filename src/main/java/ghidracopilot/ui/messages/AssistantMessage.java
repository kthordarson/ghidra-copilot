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
 * Chat bubble rendered for assistant responses.
 */
public class AssistantMessage extends AbstractChatMessage {

	private static final Color BACKGROUND = new Color(0xE5E9F0);
	private static final Color BORDER = new Color(0xCBD2DC);
	private static final Color TEXT = new Color(0x1C2333);

	public AssistantMessage(String markdown) {
		super(markdown, ChatAlignment.LEFT, BACKGROUND, BORDER, TEXT);
	}
}
