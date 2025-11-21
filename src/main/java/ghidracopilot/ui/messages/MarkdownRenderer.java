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
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Renders markdown into Swing components, using CommonMark for parsing and
 * RSyntaxTextArea for fenced code blocks.
 */
public final class MarkdownRenderer {

	private static final Pattern PLACEHOLDER_PATTERN =
		Pattern.compile("<div data-code-block=\"(\\d+)\"></div>");

	private MarkdownRenderer() {
		// utility
	}

	public static JComponent render(String markdown, Color textColor) {
		List<CodeBlockData> codeBlocks = new ArrayList<>();

		Node document = getParser().parse(markdown == null ? "" : markdown);
		String renderedHtml = getRenderer(codeBlocks).render(document);

		return buildComponent(renderedHtml, codeBlocks, textColor);
	}

	public static void applyFontStyle(JComponent component, int style) {
		if (component == null) {
			return;
		}
		updateFont(component, style);

		if (component instanceof Container container) {
			for (int i = 0; i < container.getComponentCount(); i++) {
				java.awt.Component child = container.getComponent(i);
				if (child instanceof JComponent childComponent) {
					applyFontStyle(childComponent, style);
				}
			}
		}
	}

	private static Parser getParser() {
		return Parser.builder()
			.extensions(Collections.singletonList(TablesExtension.create()))
			.build();
	}

	private static HtmlRenderer getRenderer(List<CodeBlockData> codeBlocks) {
		return HtmlRenderer.builder()
			.extensions(Collections.singletonList(TablesExtension.create()))
			.nodeRendererFactory(context -> new FencedBlockRenderer(context, codeBlocks))
			.build();
	}

	private static JComponent buildComponent(String html, List<CodeBlockData> codeBlocks,
			Color textColor) {
		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setOpaque(false);

		Matcher matcher = PLACEHOLDER_PATTERN.matcher(html);
		int lastIndex = 0;
		while (matcher.find()) {
			String before = html.substring(lastIndex, matcher.start());
			addHtmlContent(container, before, textColor);

			int index = Integer.parseInt(matcher.group(1));
			if (index >= 0 && index < codeBlocks.size()) {
				addCodeBlock(container, codeBlocks.get(index));
			}
			lastIndex = matcher.end();
		}

		String tail = html.substring(lastIndex);
		addHtmlContent(container, tail, textColor);

		return container;
	}

	private static void addHtmlContent(JPanel container, String htmlSegment, Color textColor) {
		if (htmlSegment == null) {
			return;
		}
		String trimmed = htmlSegment.trim();
		if (trimmed.isEmpty()) {
			return;
		}

		JEditorPane pane = createHtmlPane(trimmed, textColor);
		container.add(pane);
		container.add(Box.createVerticalStrut(4));
	}

	private static void addCodeBlock(JPanel container, CodeBlockData data) {
		RSyntaxTextArea textArea = new RSyntaxTextArea(data.literal()) {
			@Override
			public boolean getScrollableTracksViewportWidth() {
				return true;
			}
		};
		textArea.setSyntaxEditingStyle(resolveSyntax(data.info()));
		textArea.setEditable(false);
		textArea.setAntiAliasingEnabled(true);
		textArea.setCodeFoldingEnabled(false);
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(false);
		textArea.setBorder(new EmptyBorder(6, 8, 6, 8));
		textArea.setBackground(new Color(0xf7f9fb));

		RTextScrollPane scrollPane = new RTextScrollPane(textArea);
		scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0xd0d7de)));
		scrollPane.setFoldIndicatorEnabled(false);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);

		container.add(scrollPane);
		container.add(Box.createVerticalStrut(8));
	}

	private static JEditorPane createHtmlPane(String html, Color textColor) {
		JEditorPane pane = new JEditorPane();
		HTMLEditorKit kit = new HTMLEditorKit();
		StyleSheet styleSheet = kit.getStyleSheet();
		styleSheet.addRule(buildBodyRule(textColor));
		styleSheet.addRule("body, p, li, code, pre, table, td, th { word-break: break-word; overflow-wrap:anywhere; }");
		styleSheet.addRule("pre, code { white-space: pre-wrap; word-wrap: break-word; overflow-wrap:anywhere; }");
		styleSheet.addRule("table { table-layout: fixed; width: 100%; }");
		styleSheet.addRule("img, table { max-width: 100%; }");
		pane.setEditorKit(kit);
		pane.setContentType("text/html");
		pane.setText("<html><body>" + html + "</body></html>");
		pane.setEditable(false);
		pane.setOpaque(false);
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		return pane;
	}

	private static String buildBodyRule(Color textColor) {
		StringBuilder builder = new StringBuilder("body { margin:0; padding:0; font-family:'")
			.append(defaultFontFamily())
			.append("'; font-size:")
			.append(defaultFontSize())
			.append("pt; line-height:1.35; word-wrap:break-word; overflow-wrap:anywhere; white-space:normal;");
		if (textColor != null) {
			builder.append(" color:#")
				.append(String.format("%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue()))
				.append(';');
		}
		builder.append(" word-break: break-word; overflow-wrap:anywhere; box-sizing:border-box; max-width:100%; }");
		return builder.toString();
	}

	private static void updateFont(JComponent component, int style) {
		Font font = component.getFont();
		if (font != null) {
			component.setFont(font.deriveFont(font.getStyle() | style));
		}
	}

	private static String defaultFontFamily() {
		Font font = UIManager.getFont("Label.font");
		return font != null ? font.getFamily() : "SansSerif";
	}

	private static int defaultFontSize() {
		Font font = UIManager.getFont("Label.font");
		return font != null ? font.getSize() : 12;
	}

	private static String resolveSyntax(String info) {
		if (info == null || info.isBlank()) {
			return SyntaxConstants.SYNTAX_STYLE_NONE;
		}

		String normalized = normalizeLanguage(info);
		return switch (normalized) {
			case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
			case "kotlin" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN;
			case "c", "cpp", "c++", "cc", "h", "hpp" -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
			case "csharp", "c#" -> SyntaxConstants.SYNTAX_STYLE_CSHARP;
			case "python", "py" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
			case "javascript", "js" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
			case "typescript", "ts" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
			case "json" -> SyntaxConstants.SYNTAX_STYLE_JSON;
			case "xml", "html" -> SyntaxConstants.SYNTAX_STYLE_XML;
			case "bash", "shell", "sh", "zsh" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
			case "go", "golang" -> SyntaxConstants.SYNTAX_STYLE_GO;
			case "php" -> SyntaxConstants.SYNTAX_STYLE_PHP;
			case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
			case "ruby", "rb" -> SyntaxConstants.SYNTAX_STYLE_RUBY;
			default -> SyntaxConstants.SYNTAX_STYLE_NONE;
		};
	}

	private static String normalizeLanguage(String info) {
		String trimmed = info.trim().toLowerCase(Locale.ROOT);
		int spaceIndex = trimmed.indexOf(' ');
		if (spaceIndex >= 0) {
			trimmed = trimmed.substring(0, spaceIndex);
		}
		if (trimmed.startsWith("language-")) {
			trimmed = trimmed.substring("language-".length());
		}
		if (trimmed.startsWith(".")) {
			trimmed = trimmed.substring(1);
		}
		return trimmed;
	}

	private record CodeBlockData(String info, String literal) {
	}

	private static class FencedBlockRenderer implements NodeRenderer {

		private final HtmlNodeRendererContext context;
		private final List<CodeBlockData> codeBlocks;

		FencedBlockRenderer(HtmlNodeRendererContext context, List<CodeBlockData> codeBlocks) {
			this.context = context;
			this.codeBlocks = codeBlocks;
		}

		@Override
		public Set<Class<? extends Node>> getNodeTypes() {
			return Collections.singleton(FencedCodeBlock.class);
		}

		@Override
		public void render(Node node) {
			FencedCodeBlock block = (FencedCodeBlock) node;
			int index = codeBlocks.size();
			codeBlocks.add(new CodeBlockData(block.getInfo(), block.getLiteral()));

			HtmlWriter writer = context.getWriter();
			writer.raw("<div data-code-block=\"" + index + "\"></div>");
		}
	}
}
