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
import java.awt.Font;

/**
 * Assistant reasoning bubble (e.g., chain-of-thought summary).
 */
public class ReasoningMessage extends AbstractChatMessage {

	private static final Color BACKGROUND = new Color(0xF5F0E6);
	private static final Color BORDER = new Color(0xD8C9A7);
	private static final Color TEXT = new Color(0x4A3B1E);

	public ReasoningMessage(String markdown) {
		super(markdown, ChatAlignment.LEFT, BACKGROUND, BORDER, TEXT);
		applyItalic();
	}

	@Override
	public void setMarkdown(String markdown) {
		super.setMarkdown(markdown);
		applyItalic();
	}

	private void applyItalic() {
		MarkdownRenderer.applyFontStyle(getContentComponent(), Font.ITALIC);
	}
}
