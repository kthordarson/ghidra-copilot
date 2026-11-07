package ghidracopilot.ai.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes C-style type/declaration text into a form Ghidra's {@code DataTypeParser} accepts.
 *
 * <p>Ghidra's parser understands "basename" followed by pointer/array decorations, but not type
 * qualifiers ({@code const}, {@code volatile}, ...), tag keywords ({@code struct Foo}) or C's
 * declarator syntax where {@code *} and {@code [N]} bind to the name ({@code void *p},
 * {@code int a[4]}).</p>
 */
final class CTypeText {

	private static final Pattern QUALIFIERS = Pattern.compile(
		"\\b(?:const|volatile|restrict|__restrict|__unaligned|__ptr32|__ptr64)\\b");
	private static final Pattern TAG_KEYWORDS = Pattern.compile("\\b(?:struct|union|enum|class)\\s+");
	private static final Pattern DECLARATION = Pattern.compile(
		"^(.*?[\\w*\\s])\\s*\\b([A-Za-z_$][\\w$]*)\\s*((?:\\[[^\\]]*\\]\\s*)*)$");
	private static final Pattern BASE_NAME = Pattern.compile("^([^*\\[]+?)\\s*(?:[*\\[]|$)");

	private CTypeText() {
	}

	/** Strip qualifiers/tag keywords and collapse whitespace. */
	static String normalize(String text) {
		if (text == null) {
			return "";
		}
		String result = QUALIFIERS.matcher(text).replaceAll(" ");
		result = TAG_KEYWORDS.matcher(result).replaceAll("");
		return result.replaceAll("\\s+", " ").trim();
	}

	/**
	 * Split a normalized declaration like {@code void *begin} or {@code uint32_t counts[14]} into
	 * type text ({@code void *}, {@code uint32_t[14]}) and name. Returns null if no name is present.
	 */
	static Declaration splitDeclaration(String declaration) {
		Matcher m = DECLARATION.matcher(declaration);
		if (!m.matches()) {
			return null;
		}
		String type = m.group(1).trim().replaceAll("\\*\\s+(?=\\*)", "*");
		String name = m.group(2);
		String arrays = m.group(3).replaceAll("\\s+", "");
		if (type.isEmpty() || type.equals("*")) {
			return null;
		}
		return new Declaration(type + arrays, name);
	}

	/** Base type name without pointer/array decorations, or null. */
	static String baseName(String typeText) {
		Matcher m = BASE_NAME.matcher(typeText.trim());
		return m.find() ? m.group(1).trim() : null;
	}

	record Declaration(String typeText, String name) {
	}
}
