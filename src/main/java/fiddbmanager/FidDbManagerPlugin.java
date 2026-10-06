/* ###
 * FidDb Manager - Ghidra extension for managing Function ID databases.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package fiddbmanager;

import docking.action.builder.ActionBuilder;
import docking.tool.ToolConstants;
import ghidra.MiscellaneousPluginPackage;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.framework.plugintool.*;
import ghidra.framework.plugintool.util.PluginStatus;

/**
 * Adds a <b>Tools &rarr; FidDb Manager</b> menu for managing Function ID databases:
 * listing every installed/attached FidDb, enabling or disabling them, installing them into
 * Ghidra's FunctionID data folder, and adding new ones from a file, a directory or a URL.
 */
//@formatter:off
@PluginInfo(
	status = PluginStatus.STABLE,
	packageName = MiscellaneousPluginPackage.NAME,
	category = PluginCategoryNames.SEARCH,
	shortDescription = "FidDb Manager",
	description = "Manage Function ID databases (FidDbs): list installed and attached " +
		"databases, enable or disable them, install them into the FunctionID data folder, " +
		"and add new ones from a file, a directory or a URL."
)
//@formatter:on
public class FidDbManagerPlugin extends Plugin {

	static final String MENU_NAME = "FidDb Manager";
	private static final String GROUP_MANAGE = "a_manage";
	private static final String GROUP_ADD = "b_add";

	private FidDbManagerDialog dialog;

	public FidDbManagerPlugin(PluginTool tool) {
		super(tool);
	}

	@Override
	protected void init() {
		super.init();
		createActions();
	}

	private void createActions() {
		new ActionBuilder("Manage FidDbs", getName())
				.menuPath(ToolConstants.MENU_TOOLS, MENU_NAME, "Manage FidDbs...")
				.menuGroup(GROUP_MANAGE, "1")
				.description("List, enable/disable and install Function ID databases")
				.onAction(ac -> getDialog().showDialog())
				.buildAndInstall(tool);

		new ActionBuilder("Add FidDb From File", getName())
				.menuPath(ToolConstants.MENU_TOOLS, MENU_NAME, "Add FidDb from File...")
				.menuGroup(GROUP_ADD, "1")
				.description("Add one or more .fidb / .fidbf files")
				.onAction(ac -> {
					getDialog().showDialog();
					getDialog().addFromFile();
				})
				.buildAndInstall(tool);

		new ActionBuilder("Add FidDbs From Directory", getName())
				.menuPath(ToolConstants.MENU_TOOLS, MENU_NAME, "Add FidDbs from Directory...")
				.menuGroup(GROUP_ADD, "2")
				.description("Add every FidDb found (recursively) in a directory")
				.onAction(ac -> {
					getDialog().showDialog();
					getDialog().addFromDirectory();
				})
				.buildAndInstall(tool);

		new ActionBuilder("Add FidDb From URL", getName())
				.menuPath(ToolConstants.MENU_TOOLS, MENU_NAME, "Add FidDb from URL...")
				.menuGroup(GROUP_ADD, "3")
				.description("Download a .fidb, .fidbf or .zip of FidDbs and add it")
				.onAction(ac -> {
					getDialog().showDialog();
					getDialog().addFromUrl();
				})
				.buildAndInstall(tool);
	}

	private FidDbManagerDialog getDialog() {
		if (dialog == null) {
			dialog = new FidDbManagerDialog(tool);
		}
		return dialog;
	}

	@Override
	protected void dispose() {
		if (dialog != null) {
			dialog.dispose();
			dialog = null;
		}
		super.dispose();
	}
}
