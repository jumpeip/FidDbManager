/* ###
 * FidDb Manager - Ghidra extension for managing Function ID databases.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package fiddbmanager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import docking.widgets.table.AbstractSortedTableModel;
import ghidra.feature.fid.db.FidFile;

/**
 * Table model listing every FidDb known to Ghidra, with editable Enabled / Installed columns.
 */
public class FidDbTableModel extends AbstractSortedTableModel<FidFile> {

	static final int ENABLED_COL = 0;
	static final int INSTALLED_COL = 1;
	static final int NAME_COL = 2;
	static final int TYPE_COL = 3;
	static final int SIZE_COL = 4;
	static final int PATH_COL = 5;

	private static final String[] COLUMN_NAMES =
		{ "Enabled", "Installed", "Name", "Type", "Size", "Location" };

	/** Receives edits of the checkbox columns. */
	interface EditHandler {
		void enabledChanged(FidFile fidFile, boolean enabled);

		void installedChanged(FidFile fidFile, boolean installed);
	}

	private List<FidFile> data = new ArrayList<>();
	private final EditHandler handler;

	FidDbTableModel(EditHandler handler) {
		super(NAME_COL);
		this.handler = handler;
	}

	void setData(List<FidFile> files) {
		data = new ArrayList<>(files);
		fireTableDataChanged();
	}

	@Override
	public String getName() {
		return "FidDb Manager";
	}

	@Override
	public List<FidFile> getModelData() {
		return data;
	}

	@Override
	public int getColumnCount() {
		return COLUMN_NAMES.length;
	}

	@Override
	public String getColumnName(int column) {
		return COLUMN_NAMES[column];
	}

	@Override
	public Class<?> getColumnClass(int columnIndex) {
		switch (columnIndex) {
			case ENABLED_COL:
			case INSTALLED_COL:
				return Boolean.class;
			case SIZE_COL:
				return Long.class;
			default:
				return String.class;
		}
	}

	@Override
	public boolean isSortable(int columnIndex) {
		return true;
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		return columnIndex == ENABLED_COL || columnIndex == INSTALLED_COL;
	}

	@Override
	public void setValueAt(Object value, int rowIndex, int columnIndex) {
		if (rowIndex < 0 || rowIndex >= data.size() || !(value instanceof Boolean b)) {
			return;
		}
		FidFile fidFile = data.get(rowIndex);
		if (columnIndex == ENABLED_COL) {
			handler.enabledChanged(fidFile, b);
		}
		else if (columnIndex == INSTALLED_COL) {
			handler.installedChanged(fidFile, b);
		}
	}

	@Override
	public Object getColumnValueForRow(FidFile f, int columnIndex) {
		switch (columnIndex) {
			case ENABLED_COL:
				return f.isActive();
			case INSTALLED_COL:
				return f.isInstalled();
			case NAME_COL:
				return f.getBaseName();
			case TYPE_COL:
				return f.isInstalled() ? "Installed (" + FidDbService.RAW_EXT + ")"
						: "User attached (" + FidDbService.PACKED_EXT + ")";
			case SIZE_COL:
				File file = FidDbService.fileOf(f);
				return file.exists() ? file.length() : 0L;
			case PATH_COL:
				return f.getPath();
			default:
				return null;
		}
	}
}
