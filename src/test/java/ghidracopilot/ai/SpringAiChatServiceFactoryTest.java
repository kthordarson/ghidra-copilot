package ghidracopilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class SpringAiChatServiceFactoryTest {

	@Test
	void copilotUsesCopilotSpecificDefaultPrompt() throws Exception {
		String prompt = buildPrompt(null, AiProvider.GITHUB_COPILOT);

		assertTrue(prompt.contains("current intent/status"));
		assertFalse(prompt.contains("Always call report_intent"));
	}

	@Test
	void copilotRewritesLegacyDefaultPromptToCopilotIntentGuidance() throws Exception {
		String prompt = buildPrompt(SpringAiChatServiceFactory.DEFAULT_SYSTEM_PROMPT,
			AiProvider.GITHUB_COPILOT);

		assertTrue(prompt.contains("current intent/status"));
		assertFalse(prompt.contains("Always call report_intent"));
	}

	@Test
	void nonCopilotBackendsKeepExplicitReportIntentGuidance() throws Exception {
		String prompt = buildPrompt(null, AiProvider.OPENAI);

		assertEquals(SpringAiChatServiceFactory.DEFAULT_SYSTEM_PROMPT, prompt);
		assertTrue(prompt.contains("Always call report_intent"));
	}

	private static String buildPrompt(String configuredPrompt, AiProvider provider) throws Exception {
		Method method = SpringAiChatServiceFactory.class.getDeclaredMethod(
			"buildEffectiveSystemPrompt", String.class, AiProvider.class);
		method.setAccessible(true);
		return (String) method.invoke(null, configuredPrompt, provider);
	}
}
