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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

import javax.swing.border.AbstractBorder;

/**
 * Rounded border used to render chat bubbles with smoother corners.
 */
class RoundedBubbleBorder extends AbstractBorder {

	private final Color borderColor;
	private final int arcRadius;
	private final int strokeWidth;
	RoundedBubbleBorder(Color borderColor, int arcRadius, int strokeWidth) {
		this.borderColor = borderColor != null ? borderColor : Color.GRAY;
		this.arcRadius = Math.max(arcRadius, 8);
		this.strokeWidth = Math.max(strokeWidth, 1);
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(borderColor);
			g2.setStroke(new BasicStroke(strokeWidth));
			int offset = strokeWidth / 2;
			int drawWidth = width - strokeWidth;
			int drawHeight = height - strokeWidth;
			g2.drawRoundRect(x + offset, y + offset, drawWidth, drawHeight, arcRadius, arcRadius);
		}
		finally {
			g2.dispose();
		}
	}

	@Override
	public Insets getBorderInsets(Component c) {
		int pad = Math.max(1, strokeWidth);
		return new Insets(pad, pad, pad, pad);
	}

	@Override
	public Insets getBorderInsets(Component c, Insets insets) {
		Insets pad = getBorderInsets(c);
		insets.top = pad.top;
		insets.left = pad.left;
		insets.bottom = pad.bottom;
		insets.right = pad.right;
		return insets;
	}

	@Override
	public boolean isBorderOpaque() {
		return false;
	}

}
