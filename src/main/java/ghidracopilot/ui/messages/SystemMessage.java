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
 * Lightweight status message shown in the chat transcript (e.g., connection updates).
 */
public class SystemMessage extends AbstractChatMessage {

	private static final Color TEXT = new Color(0x5B6573);

	public SystemMessage(String markdown) {
		super(markdown, ChatAlignment.CENTER, null, null, TEXT, false);
	}
}
