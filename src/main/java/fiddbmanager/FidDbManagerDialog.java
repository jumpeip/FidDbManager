/* ###
 * FidDb Manager - Ghidra extension for managing Function ID databases.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package fiddbmanager;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Function;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import docking.DialogComponentProvider;
import docking.widgets.OptionDialog;
import docking.widgets.filechooser.GhidraFileChooser;
import docking.widgets.filechooser.GhidraFileChooserMode;
import docking.widgets.table.GTable;
import ghidra.feature.fid.db.FidFile;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.filechooser.ExtensionFileFilter;
import ghidra.util.task.*;

/**
 * Non-modal dialog that lists all FidDbs and lets the user add, enable/disable,
 * install/uninstall and detach them.
 */
public final class FidDbManagerDialog extends DialogComponentProvider
		implements ChangeListener, FidDbTableModel.EditHandler {

	private static final String LAST_DIR_PREF = "FidDbManager.LastDirectory";

	private final PluginTool tool;
	private final FidDbService service = new FidDbService();
	private final FidDbTableModel model;
	private final GTable table;
	private final JCheckBox installOnAddCheckbox;
	private JButton removeButton;
	private boolean listening;

	public FidDbManagerDialog(PluginTool tool) {
		super("FidDb Manager", false, true, true, true);
		this.tool = tool;
		this.model = new FidDbTableModel(this);
		this.table = new GTable(model);
		this.installOnAddCheckbox =
			new JCheckBox("Install added databases into Ghidra's FunctionID data folder");

		addWorkPanel(buildMainPanel());
		addDismissButton();
		setRememberSize(true);
		setPreferredSize(1000, 450);
	}

	private JComponent buildMainPanel() {
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.getSelectionModel().addListSelectionListener(e -> updateButtons());
		table.getColumnModel().getColumn(FidDbTableModel.ENABLED_COL).setMaxWidth(70);
		table.getColumnModel().getColumn(FidDbTableModel.INSTALLED_COL).setMaxWidth(70);
		table.getColumnModel().getColumn(FidDbTableModel.TYPE_COL).setPreferredWidth(150);
		table.getColumnModel().getColumn(FidDbTableModel.SIZE_COL).setMaxWidth(110);
		table.getColumnModel().getColumn(FidDbTableModel.PATH_COL).setPreferredWidth(420);

		JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
		buttons.add(button("Add File...", "Add a .fidb or .fidbf file", this::addFromFile));
		buttons.add(button("Add Directory...", "Add every FidDb found under a folder",
			this::addFromDirectory));
		buttons.add(button("Add URL...", "Download a .fidb, .fidbf or .zip and add it",
			this::addFromUrl));
		buttons.add(Box.createVerticalStrut(8));
		removeButton = button("Remove", "Detach user databases / uninstall installed ones",
			this::removeSelected);
		buttons.add(removeButton);
		buttons.add(Box.createVerticalStrut(8));
		buttons.add(button("Enable All", "Use every FidDb during Function ID analysis",
			() -> setAllActive(true)));
		buttons.add(button("Disable All", "Don't use any FidDb during Function ID analysis",
			() -> setAllActive(false)));
		buttons.add(button("Refresh", "Reload the list", this::refresh));

		JPanel east = new JPanel(new BorderLayout());
		east.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
		east.add(buttons, BorderLayout.NORTH);

		JPanel south = new JPanel(new BorderLayout());
		south.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		south.add(installOnAddCheckbox, BorderLayout.WEST);
		String dataDir;
		try {
			dataDir = FidDbService.getFunctionIdDataDir().getAbsolutePath();
		}
		catch (IOException e) {
			dataDir = "(unavailable: " + e.getMessage() + ")";
		}
		JLabel dataDirLabel = new JLabel("Data folder: " + dataDir);
		dataDirLabel.setToolTipText(dataDir);
		south.add(dataDirLabel, BorderLayout.SOUTH);

		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		panel.add(new JScrollPane(table), BorderLayout.CENTER);
		panel.add(east, BorderLayout.EAST);
		panel.add(south, BorderLayout.SOUTH);
		return panel;
	}

	private static JButton button(String text, String tip, Runnable r) {
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.addActionListener(e -> r.run());
		return b;
	}

	//==================================================================================================
	// Showing / lifecycle
	//==================================================================================================

	public void showDialog() {
		if (!listening) {
			service.getFidFileManager().addChangeListener(this);
			listening = true;
		}
		refresh();
		if (isVisible()) {
			toFront();
		}
		else {
			tool.showDialog(this);
		}
	}

	// Dismiss, Escape and the window's close box only hide the dialog so it can be reopened from
	// the menu. The base close() disposes the dialog's actions, so it's reserved for dispose().
	@Override
	protected void dismissCallback() {
		closeDialog();
	}

	@Override
	protected void cancelCallback() {
		closeDialog();
	}

	@Override
	protected void dialogClosed() {
		stopListening();
	}

	/** Permanently tears the dialog down; called when the plugin is disposed. */
	@Override
	public void dispose() {
		stopListening();
		super.dispose();
	}

	private void stopListening() {
		if (listening) {
			service.getFidFileManager().removeChangeListener(this);
			listening = false;
		}
	}

	@Override
	public void stateChanged(ChangeEvent e) {
		Swing.runLater(this::refresh);
	}

	void refresh() {
		List<FidFile> selected = table.getSelectedRowCount() > 0 ? selectedFiles() : List.of();
		model.setData(service.getFidFiles());
		for (FidFile f : selected) {
			int row = model.getRowIndex(f);
			if (row >= 0) {
				table.addRowSelectionInterval(row, row);
			}
		}
		long active = model.getModelData().stream().filter(FidFile::isActive).count();
		setStatusText(model.getRowCount() + " FidDbs, " + active + " enabled");
		updateButtons();
	}

	private void updateButtons() {
		if (removeButton != null) {
			removeButton.setEnabled(table.getSelectedRowCount() > 0);
		}
	}

	private List<FidFile> selectedFiles() {
		List<FidFile> list = new ArrayList<>();
		for (int row : table.getSelectedRows()) {
			FidFile f = model.getRowObject(row);
			if (f != null) {
				list.add(f);
			}
		}
		return list;
	}

	//==================================================================================================
	// Table edits
	//==================================================================================================

	@Override
	public void enabledChanged(FidFile fidFile, boolean enabled) {
		fidFile.setActive(enabled);
		refresh();
	}

	@Override
	public void installedChanged(FidFile fidFile, boolean installed) {
		if (installed == fidFile.isInstalled()) {
			return;
		}
		if (installed) {
			if (!checkDataDirWritable()) {
				return;
			}
			runTask("Installing " + fidFile.getName(),
				monitor -> List.of(service.install(fidFile, this::confirmOverwrite, monitor)));
		}
		else {
			uninstall(List.of(fidFile));
		}
	}

	//==================================================================================================
	// Actions (also invoked from the plugin's menu items)
	//==================================================================================================

	public void addFromFile() {
		GhidraFileChooser chooser = new GhidraFileChooser(getComponentForParent());
		chooser.setTitle("Add FidDb File(s)");
		chooser.setApproveButtonText("Add");
		chooser.setFileSelectionMode(GhidraFileChooserMode.FILES_ONLY);
		chooser.setMultiSelectionEnabled(true);
		chooser.addFileFilter(ExtensionFileFilter.forExtensions("Function ID databases",
			"fidb", "fidbf"));
		chooser.setLastDirectoryPreference(LAST_DIR_PREF);
		List<File> files = chooser.getSelectedFiles();
		chooser.dispose();
		if (files == null || files.isEmpty()) {
			return;
		}
		if (!checkWritableIfNeeded(files)) {
			return;
		}
		boolean install = installOnAddCheckbox.isSelected();
		runTask("Adding FidDbs", monitor -> {
			List<String> results = new ArrayList<>();
			for (File f : files) {
				monitor.checkCancelled();
				try {
					results.add(service.addFile(f, install, this::confirmOverwrite, monitor));
				}
				catch (IOException e) {
					results.add("FAILED " + f.getName() + ": " + e.getMessage());
				}
			}
			return results;
		});
	}

	public void addFromDirectory() {
		GhidraFileChooser chooser = new GhidraFileChooser(getComponentForParent());
		chooser.setTitle("Add All FidDbs in Directory");
		chooser.setApproveButtonText("Add Directory");
		chooser.setFileSelectionMode(GhidraFileChooserMode.DIRECTORIES_ONLY);
		chooser.setLastDirectoryPreference(LAST_DIR_PREF);
		File dir = chooser.getSelectedFile();
		chooser.dispose();
		if (dir == null) {
			return;
		}
		boolean install = installOnAddCheckbox.isSelected();
		if (install && !checkDataDirWritable()) {
			return;
		}
		runTask("Adding FidDbs from " + dir.getName(),
			monitor -> service.addDirectory(dir, install, this::confirmOverwrite, monitor));
	}

	public void addFromUrl() {
		String url = OptionDialog.showInputSingleLineDialog(getComponentForParent(),
			"Add FidDb from URL",
			"URL of a .fidb, .fidbf or .zip file:", "https://");
		if (url == null || url.isBlank() || url.trim().equals("https://")) {
			return;
		}
		boolean install = installOnAddCheckbox.isSelected();
		if (install && !checkDataDirWritable()) {
			return;
		}
		runTask("Downloading FidDb",
			monitor -> service.addFromUrl(url, install, this::confirmOverwrite, monitor));
	}

	private void removeSelected() {
		List<FidFile> selected = selectedFiles();
		if (selected.isEmpty()) {
			return;
		}
		List<FidFile> user = selected.stream().filter(f -> !f.isInstalled()).toList();
		List<FidFile> installed = selected.stream().filter(FidFile::isInstalled).toList();

		if (!user.isEmpty()) {
			int choice = OptionDialog.showOptionDialog(getComponentForParent(), "Detach FidDbs",
				"Detach " + user.size() + " user database(s) from Ghidra?\n" +
					"The files themselves are not deleted.",
				"Detach", OptionDialog.QUESTION_MESSAGE);
			if (choice == OptionDialog.OPTION_ONE) {
				user.forEach(service::detach);
			}
		}
		if (!installed.isEmpty()) {
			uninstall(installed);
		}
		refresh();
	}

	private void uninstall(List<FidFile> files) {
		StringBuilder names = new StringBuilder();
		boolean shipped = false;
		for (FidFile f : files) {
			names.append("\n  ").append(f.getName());
			shipped |= !FidDbService.isInDataDir(f);
		}
		String msg = "Uninstall the following database(s)?" + names +
			"\n\nThe files will be moved out of the Ghidra installation into a backup folder " +
			"in your user settings directory, not deleted.";
		if (shipped) {
			msg += "\n\nNote: some of these live outside the FunctionID data folder.";
		}
		int choice = OptionDialog.showOptionDialog(getComponentForParent(), "Uninstall FidDbs",
			msg, "Uninstall", OptionDialog.WARNING_MESSAGE);
		if (choice != OptionDialog.OPTION_ONE) {
			refresh(); // revert the checkbox
			return;
		}
		List<String> results = new ArrayList<>();
		for (FidFile f : files) {
			try {
				File backup = service.uninstall(f);
				results.add("Uninstalled " + f.getName() + " (backup: " + backup + ")");
			}
			catch (IOException e) {
				results.add("FAILED " + f.getName() + ": " + e.getMessage());
			}
		}
		refresh();
		showResults("Uninstall FidDbs", results);
	}

	private void setAllActive(boolean active) {
		for (FidFile f : service.getFidFiles()) {
			if (f.isActive() != active) {
				f.setActive(active);
			}
		}
		refresh();
	}

	//==================================================================================================
	// Helpers
	//==================================================================================================

	private interface Work {
		List<String> run(TaskMonitor monitor) throws IOException, CancelledException;
	}

	private void runTask(String title, Work work) {
		List<String> results = new ArrayList<>();
		Task task = new Task(title, true, true, true) {
			@Override
			public void run(TaskMonitor monitor) throws CancelledException {
				try {
					results.addAll(work.run(monitor));
				}
				catch (IOException e) {
					results.add("FAILED: " + e.getMessage());
					Msg.error(FidDbManagerDialog.this, title + " failed", e);
				}
			}
		};
		new TaskLauncher(task, getComponentForParent());
		if (task.isCancelled()) {
			results.add("Cancelled");
		}
		refresh();
		showResults(title, results);
	}

	private void showResults(String title, List<String> results) {
		if (results.isEmpty()) {
			return;
		}
		boolean failed = results.stream().anyMatch(r -> r.startsWith("FAILED"));
		String text = String.join("\n", results);
		if (results.size() == 1 && !failed) {
			setStatusText(text);
			return;
		}
		Function<String, String> trim =
			s -> s.length() > 4000 ? s.substring(0, 4000) + "\n..." : s;
		if (failed) {
			Msg.showWarn(this, getComponentForParent(), title, trim.apply(text));
		}
		else {
			Msg.showInfo(this, getComponentForParent(), title, trim.apply(text));
		}
	}

	/** Called from task threads when an install target already exists. */
	private boolean confirmOverwrite(File existing) {
		return Swing.runNow(() -> OptionDialog.showOptionDialog(getComponentForParent(),
			"FidDb Already Installed",
			existing.getName() + " already exists in the FunctionID data folder.\n" +
				"Replace it?",
			"Replace", OptionDialog.QUESTION_MESSAGE) == OptionDialog.OPTION_ONE);
	}

	private boolean checkWritableIfNeeded(List<File> files) {
		boolean needsInstall = installOnAddCheckbox.isSelected() || files.stream()
				.anyMatch(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(FidDbService.RAW_EXT));
		return !needsInstall || checkDataDirWritable();
	}

	private boolean checkDataDirWritable() {
		try {
			File dir = FidDbService.getFunctionIdDataDir();
			if (!dir.canWrite()) {
				Msg.showError(this, getComponentForParent(), "Data Folder Not Writable",
					"Can't write to " + dir + "\nRun Ghidra with permission to modify its " +
						"installation folder to install databases.");
				return false;
			}
			return true;
		}
		catch (IOException e) {
			Msg.showError(this, getComponentForParent(), "Data Folder Unavailable",
				e.getMessage());
			return false;
		}
	}

	private Component getComponentForParent() {
		return isVisible() ? getComponent() : tool.getActiveWindow();
	}
}
