package ghidracopilot;

import ghidra.framework.plugintool.util.PluginPackage;
import resources.ResourceManager;

public class CopilotPluginPackage extends PluginPackage {
	public static final String NAME = "Copilot";

	public CopilotPluginPackage() {
		super(NAME, ResourceManager.loadImage("images/applications-internet.png"),
			"AI-powered reverse engineering assistant.",
			FEATURE_PRIORITY);
	}
}
