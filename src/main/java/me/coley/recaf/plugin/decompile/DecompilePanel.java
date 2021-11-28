package me.coley.recaf.plugin.decompile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import me.coley.recaf.control.gui.GuiController;
import me.coley.recaf.decompile.Decompiler;
import me.coley.recaf.decompile.fernflower.FernFlowerDecompiler;
import me.coley.recaf.ui.controls.ExceptionAlert;
import me.coley.recaf.util.EscapeUtil;
import me.coley.recaf.util.LangUtil;
import me.coley.recaf.util.Log;
import me.coley.recaf.util.UiUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.jar.JarEntry;
import java.util.zip.ZipOutputStream;

/**
 * UI for decompilation progress.
 *
 * @author Matt Coley
 */
public class DecompilePanel extends BorderPane {
	private static final Color SUCCESS_COLOR = new Color(0.05, 0.45, 0.05, 1);
	private static final Color FAIL_COLOR = new Color(0.5, 0.05, 0.05, 1);
	private final int classCount;
	private final List<Long> decompileTimes = new CopyOnWriteArrayList<>();
	private final FileChooser saver = new FileChooser();
	private final Map<String, String> classSources = new ConcurrentHashMap<>();
	private final Set<String> failedDecompiles = new ConcurrentSkipListSet<>();
	private final ListView<String> list = new ListView<>();
	private final Label lblEstimatedTime = new Label("Remaining time: Unknown");
	private final ProgressBar progressBar = new ProgressBar(0);
	private final GuiController controller;
	private final Stage window;

	public DecompilePanel(GuiController controller, BatchDecompile plugin, TreeSet<String> classNames) {
		this.controller = controller;
		this.classCount = classNames.size();
		list.setItems(FXCollections.observableArrayList(classNames));
		list.setCellFactory(e -> new DecompileListCell());
		FileChooser.ExtensionFilter filter = classNames.size() > 1 ?
				new FileChooser.ExtensionFilter("Sources", "*.jar", "*.zip", "*.java") :
				new FileChooser.ExtensionFilter("Source", "*.java");
		saver.setTitle(LangUtil.translate("misc.save"));
		saver.getExtensionFilters().add(filter);
		saver.setSelectedExtensionFilter(filter);
		setCenter(list);
		BorderPane bottomInfo = new BorderPane();
		bottomInfo.setCenter(progressBar);
		bottomInfo.setBottom(lblEstimatedTime);
		setBottom(bottomInfo);
		progressBar.setMaxWidth(Double.MAX_VALUE);
		// Show window
		window = controller.windows().window("Decompile export", this);
		window.show();
		// Start decompile
		new Thread(() -> startDecompile(plugin, classNames)).start();
	}

	private void startDecompile(BatchDecompile plugin, TreeSet<String> strings) {
		// Setup thread count
		int nThreads = Math.max(1, Math.min((int) plugin.minimumThreads, Runtime.getRuntime().availableProcessors()));
		ExecutorService threadPool = Executors.newFixedThreadPool(nThreads);
		ScheduledExecutorService cancelService = Executors.newScheduledThreadPool(nThreads);
		// Setup decompiler
		Decompiler<?> decompiler = createDecompiler(plugin);
		// Decompile each class
		for (String name : strings) {
			Future<?> future;
			if (decompiler instanceof FernFlowerDecompiler) {
				// FernFlower's usage of ThreadLocal forces us to re-allocate a new instance
				// of the decompiler per-each thread...
				future = threadPool.submit(() -> decompile(createDecompiler(plugin), name));
			} else {
				future = threadPool.submit(() -> decompile(decompiler, name));
			}
			// Abort if the class takes too long to decompile
			cancelService.schedule(() -> {
				// Skip on success
				if (future.isDone())
					return;
				// Kill future
				future.cancel(true);
				// Update state of failed decompilation
				failedDecompiles.add(name);
				classSources.put(name, "public class " + name.substring(name.lastIndexOf('/') + 1) + " {\n" +
						"\t// Failed to decompile, took too long to decompile: " + name + "\n" +
						"}");
				decompileTimes.add(plugin.classTimeout);
			}, plugin.classTimeout, TimeUnit.SECONDS);
		}
		threadPool.shutdown();
		cancelService.shutdown();
		try {
			threadPool.awaitTermination(1, TimeUnit.DAYS);
			Platform.runLater(this::saveSources);
		} catch (InterruptedException e) {
			Log.error("Interrupted decompile service");
		}
	}

	private Decompiler<?> createDecompiler(BatchDecompile plugin) {
		if (plugin.usePreferredDecompiler) {
			return plugin.preferredDecompiler.create(controller);
		}
		return controller.config().decompile().decompiler.create(controller);
	}

	private void decompile(Decompiler<?> decompiler, String name) {
		long start = System.currentTimeMillis();
		try {
			String decompiled = decompiler.decompile(name);
			classSources.put(name, EscapeUtil.unescapeUnicode(decompiled));
			decompileTimes.add(System.currentTimeMillis() - start);
			updateProgressUI();
		} catch (Exception ex) {
			Log.error(ex, "Class failed to decompiled: {}", name);
			failedDecompiles.add(name);
			classSources.put(name, "public class " + name.substring(name.lastIndexOf('/') + 1) + " {\n" +
					"\t// Failed to decompile: " + name + "\n" +
					"}");
			decompileTimes.add(System.currentTimeMillis() - start);
			updateProgressUI();
		}
	}

	/**
	 * Called when the {@link #decompileTimes} map is updated.
	 * Updates the UI with an estimation of time to completion, along with the progress bar.
	 */
	private void updateProgressUI() {
		int done = decompileTimes.size();
		int remaining = classCount - done;
		OptionalDouble avgMillis = decompileTimes.stream().mapToLong(Long::longValue)
				.average();
		if (avgMillis.isPresent()) {
			long etaRemaining = (long) (avgMillis.getAsDouble() * remaining);
			String hms = String.format("%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(etaRemaining),
					TimeUnit.MILLISECONDS.toMinutes(etaRemaining) % TimeUnit.HOURS.toMinutes(1),
					TimeUnit.MILLISECONDS.toSeconds(etaRemaining) % TimeUnit.MINUTES.toSeconds(1));
			Platform.runLater(() -> lblEstimatedTime.setText("Estimated remaining time: " + hms));
		}
		Platform.runLater(() -> {
			list.refresh();
			progressBar.setProgress(classSources.size() / (double) classCount);
		});
	}

	private void saveSources() {
		saver.setInitialDirectory(controller.config().backend().getRecentSaveAppDir());
		File file = saver.showSaveDialog(null);
		if (file != null) {
			try {
				if (classCount == 1) {
					Files.write(file.toPath(), classSources.get(classSources.keySet().iterator().next()).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
				} else {
					try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
						for (Map.Entry<String, String> e : classSources.entrySet()) {
							zos.putNextEntry(new JarEntry(e.getKey() + ".java"));
							zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
							zos.closeEntry();
						}
					}
				}
			} catch (Exception ex) {
				Log.error(ex, "Failed to save sources to file: {}", file.getName());
				ExceptionAlert.show(ex, "Failed to save application to file: " + file.getName());
			}
		}
		// Done, can close
		window.close();
	}

	private class DecompileListCell extends ListCell<String> {
		@Override
		protected void updateItem(String item, boolean empty) {
			super.updateItem(item, empty);
			if (empty) {
				setText(null);
				setGraphic(null);
			} else {
				setText(item);
				setGraphic(UiUtil.createClassGraphic(controller.getWorkspace().getClassReader(item).getAccess()));
				// Change color based on state:
				// - not done = no color
				// - done = green
				// - failed = red
				if (classSources.containsKey(item)) {
					Color color = failedDecompiles.contains(item) ? FAIL_COLOR : SUCCESS_COLOR;
					int r = (int) (color.getRed() * 255);
					int g = (int) (color.getGreen() * 255);
					int b = (int) (color.getBlue() * 255);
					setStyle("-fx-background: rgb(" + r + "," + g + "," + b + ")");
				}
			}
		}
	}
}