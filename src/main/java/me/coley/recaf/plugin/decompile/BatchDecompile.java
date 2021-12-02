package me.coley.recaf.plugin.decompile;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import me.coley.recaf.config.Conf;
import me.coley.recaf.config.FieldWrapper;
import me.coley.recaf.control.Controller;
import me.coley.recaf.control.gui.GuiController;
import me.coley.recaf.decompile.DecompileImpl;
import me.coley.recaf.ui.ContextBuilder;
import me.coley.recaf.ui.controls.ActionMenuItem;
import me.coley.recaf.ui.controls.NumberSlider;
import me.coley.recaf.ui.controls.NumericText;
import me.coley.recaf.workspace.JavaResource;
import org.plugface.core.annotations.Plugin;
import me.coley.recaf.plugin.api.*;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A plugin that adds context menus to decompile a class, a package, or the entire program all at once.
 * The results are bundled as a ZIP file and placed at a requested location.
 *
 * @author Matt Coley
 */
@Plugin(name = "Batch Decompile")
public class BatchDecompile implements StartupPlugin, ContextMenuInjectorPlugin, ConfigurablePlugin {
	private static final String MIN_T_COUNT = "Minimum thread count";
	private static final String CLASS_TIMEOUT = "Class timeout (seconds)";
	private Controller controller;

	@Conf(value = MIN_T_COUNT, noTranslate = true)
	public long minimumThreads = 2;

	@Conf(value = CLASS_TIMEOUT, noTranslate = true)
	public long classTimeout = 10;

	@Conf(value = "Use preferred decompiler", noTranslate = true)
	public boolean usePreferredDecompiler;

	@Conf(value = "Preferred decompiler", noTranslate = true)
	public DecompileImpl preferredDecompiler = DecompileImpl.CFR;

	@Override
	public String getVersion() {
		return "1.2.1";
	}

	@Override
	public String getDescription() {
		return "Provides an decompile export function on selected classes/packages/resources";
	}

	@Override
	public void onStart(Controller controller) {
		this.controller = controller;
	}

	@Override
	public void addFieldEditors(Map<String, Function<FieldWrapper, Node>> editors) {
		int max = Runtime.getRuntime().availableProcessors();
		editors.put(MIN_T_COUNT, field -> new NumberSlider<Integer>((GuiController) controller, field, 1, max, 1));
		editors.put(CLASS_TIMEOUT, field -> new NumberSlider<Integer>((GuiController) controller, field, 5, 100, 5));
	}

	@Override
	public void forPackage(ContextBuilder builder, ContextMenu menu, String name) {
		menu.getItems().add(new ActionMenuItem("Decompile package",
				() -> decompile(Pattern.quote(name) + "/.*", builder.getResource())));
	}

	@Override
	public void forClass(ContextBuilder builder, ContextMenu menu, String name) {
		menu.getItems().add(new ActionMenuItem("Decompile class",
				() -> decompile(Pattern.quote(name), builder.getResource())));
	}

	@Override
	public void forResourceRoot(ContextBuilder builder, ContextMenu menu, JavaResource resource) {
		menu.getItems().add(new ActionMenuItem("Decompile all",
				() -> decompile(".*", resource)));
	}

	private void decompile(String namePattern, JavaResource resource) {
		Set<String> matchedNames = resource.getClasses().keySet().stream()
				.filter(name -> name.matches(namePattern))
				.collect(Collectors.toSet());
		new DecompilePanel((GuiController) controller, this, new TreeSet<>(matchedNames));
	}

	@Override
	public String getConfigTabTitle() {
		return "Batch Decompile";
	}
}