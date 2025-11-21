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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Panel that renders a speech-bubble chrome with a directional tail.
 */
class SpeechBubblePanel extends JPanel {

	private static final int ARC_RADIUS = 18;
	private static final int STROKE_WIDTH = 1;
	private static final int TAIL_WIDTH = 16;
	private static final int TAIL_OVERLAP = 8;
	private static final int TAIL_HEIGHT = 12;
	private static final int HORIZONTAL_PADDING = 16;
	private static final int VERTICAL_PADDING = 12;

	private final ChatAlignment alignment;
	private final Color backgroundColor;
	private final Color borderColor;

	SpeechBubblePanel(ChatAlignment alignment, Color backgroundColor, Color borderColor) {
		super(new BorderLayout());

		this.alignment = alignment != null ? alignment : ChatAlignment.CENTER;
		this.backgroundColor = backgroundColor != null ? backgroundColor : Color.WHITE;
		this.borderColor = borderColor != null ? borderColor : this.backgroundColor.darker();

		setOpaque(false);

		int leftPad = HORIZONTAL_PADDING +
			(this.alignment == ChatAlignment.LEFT ? Math.max(0, TAIL_WIDTH - TAIL_OVERLAP) : 0);
		int rightPad = HORIZONTAL_PADDING +
			(this.alignment == ChatAlignment.RIGHT ? Math.max(0, TAIL_WIDTH - TAIL_OVERLAP) : 0);
		setBorder(new EmptyBorder(VERTICAL_PADDING, leftPad, VERTICAL_PADDING, rightPad));
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int width = getWidth();
			int height = getHeight();
			if (width <= 0 || height <= 0) {
				return;
			}

			boolean tailLeft = alignment == ChatAlignment.LEFT;
			boolean tailRight = alignment == ChatAlignment.RIGHT;
			int tailWidth = (tailLeft || tailRight) ? TAIL_WIDTH : 0;

			double strokeOffset = STROKE_WIDTH / 2.0;
			double overlap = tailWidth > 0 ? TAIL_OVERLAP : 0;
			double bodyX = tailLeft ? Math.max(strokeOffset, tailWidth - overlap + strokeOffset) : strokeOffset;
			double bodyWidth = width - (tailLeft ? tailWidth - overlap : 0) - (tailRight ? tailWidth - overlap : 0)
					- STROKE_WIDTH;
			double bodyHeight = height - STROKE_WIDTH;
			if (bodyWidth <= 0 || bodyHeight <= 0) {
				return;
			}

			RoundRectangle2D body = new RoundRectangle2D.Double(bodyX, strokeOffset, bodyWidth, bodyHeight,
				ARC_RADIUS * 2.0, ARC_RADIUS * 2.0);

			Area bubble = new Area(body);

			if (tailLeft || tailRight) {
				double availableSpan = bodyHeight - ARC_RADIUS;
				double tailSpan = Math.min(TAIL_HEIGHT, Math.max(6.0, availableSpan));
				double tailBottom = height - strokeOffset - ARC_RADIUS * 0.35;
				double tailTop = tailBottom - tailSpan;
				double minTailTop = strokeOffset + ARC_RADIUS * 0.3;
				if (tailTop < minTailTop) {
					tailTop = minTailTop;
					tailBottom = tailTop + tailSpan;
				}
				double maxTailBottom = height - strokeOffset - ARC_RADIUS * 0.2;
				if (tailBottom > maxTailBottom) {
					tailBottom = maxTailBottom;
					tailTop = tailBottom - tailSpan;
				}
				double tailMid = (tailTop + tailBottom) / 2.0;
				double bodyEdgeX = tailLeft ? body.getX() + TAIL_OVERLAP :
					body.getX() + bodyWidth - TAIL_OVERLAP;
				double tipX = tailLeft ? strokeOffset : width - strokeOffset;

				Path2D tail = new Path2D.Double(Path2D.WIND_NON_ZERO);
				tail.moveTo(bodyEdgeX, tailTop);
				tail.lineTo(tipX, tailMid);
				tail.lineTo(bodyEdgeX, tailBottom);
				tail.closePath();
				bubble.add(new Area(tail));
			}

			g2.setColor(backgroundColor);
			g2.fill(bubble);

			g2.setColor(borderColor);
			g2.setStroke(new BasicStroke(STROKE_WIDTH));
			g2.draw(bubble);
		}
		finally {
			g2.dispose();
		}
	}
}
