/* ###
 * FidDb Manager - Ghidra extension for managing Function ID databases.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package fiddbmanager;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import generic.jar.ResourceFile;
import ghidra.feature.fid.db.*;
import ghidra.framework.Application;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.VersionException;
import ghidra.util.task.TaskMonitor;

/**
 * Core (non-UI) logic for adding, installing, uninstalling and downloading FidDb files.
 * <p>
 * Ghidra knows two kinds of FidDb:
 * <ul>
 * <li><b>Installed</b> raw read-only databases ({@code .fidbf}) that live in a module's
 * {@code data} directory (normally {@code Ghidra/Features/FunctionID/data}) and are discovered
 * automatically at startup.</li>
 * <li><b>User-attached</b> packed databases ({@code .fidb}) that can live anywhere and are
 * remembered in the user's preferences.</li>
 * </ul>
 * "Installing" a database therefore means writing a raw {@code .fidbf} copy of it into the
 * FunctionID data directory.
 */
public class FidDbService {

	public static final String PACKED_EXT = FidFile.FID_PACKED_DATABASE_FILE_EXTENSION; // .fidb
	public static final String RAW_EXT = FidFile.FID_RAW_DATABASE_FILE_EXTENSION;       // .fidbf

	private static final String FUNCTION_ID_MODULE = "FunctionID";
	private static final String SETTINGS_SUBDIR = "FidDbManager";

	/** Decides what to do when an install target already exists. */
	public interface ConflictResolver {
		/** @return true to overwrite {@code existing}, false to skip */
		boolean overwrite(File existing);
	}

	private final FidFileManager fidFileManager = FidFileManager.getInstance();

	public FidFileManager getFidFileManager() {
		return fidFileManager;
	}

	public List<FidFile> getFidFiles() {
		return fidFileManager.getFidFiles();
	}

	//==================================================================================================
	// Locations
	//==================================================================================================

	/**
	 * {@return the Function ID data directory of this Ghidra installation (created if missing)}
	 * @throws IOException if the FunctionID module can't be found or the directory can't be created
	 */
	public static File getFunctionIdDataDir() throws IOException {
		ResourceFile moduleRoot = Application.getModuleRootDir(FUNCTION_ID_MODULE);
		if (moduleRoot == null) {
			throw new IOException("The " + FUNCTION_ID_MODULE + " module was not found in this " +
				"Ghidra installation.");
		}
		File dataDir = new File(moduleRoot.getFile(false), "data");
		if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
			throw new IOException("Unable to create " + dataDir);
		}
		return dataDir;
	}

	/** {@return a per-user working directory for this extension (downloads, backups)} */
	public static File getSettingsDir(String child) throws IOException {
		File dir = new File(new File(Application.getUserSettingsDirectory(), SETTINGS_SUBDIR), child);
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("Unable to create " + dir);
		}
		return dir;
	}

	/** {@return the on-disk location of a FidFile} */
	public static File fileOf(FidFile fidFile) {
		return new File(fidFile.getPath());
	}

	/** {@return true if the FidFile physically lives in the FunctionID data directory} */
	public static boolean isInDataDir(FidFile fidFile) {
		try {
			File dataDir = getFunctionIdDataDir().getCanonicalFile();
			File parent = fileOf(fidFile).getCanonicalFile().getParentFile();
			return dataDir.equals(parent);
		}
		catch (IOException e) {
			return false;
		}
	}

	/** {@return the raw file an install of {@code fidFile} would write} */
	public static File installTargetFor(FidFile fidFile) throws IOException {
		return new File(getFunctionIdDataDir(), fidFile.getBaseName() + RAW_EXT);
	}

	/** {@return the raw file an install of the given raw source file would write} */
	public static File installTargetFor(File rawSource) throws IOException {
		return new File(getFunctionIdDataDir(), rawSource.getName());
	}

	public static boolean isFidDbFile(File f) {
		String n = f.getName().toLowerCase(Locale.ROOT);
		return f.isFile() && (n.endsWith(PACKED_EXT) || n.endsWith(RAW_EXT));
	}

	//==================================================================================================
	// Adding
	//==================================================================================================

	/**
	 * Adds a single FidDb file. Packed {@code .fidb} files are attached as user databases (and
	 * optionally installed); raw {@code .fidbf} files can only be used from the data directory,
	 * so they are always installed.
	 *
	 * @param file the file to add
	 * @param install true to also install a packed database into the data directory
	 * @param resolver decides about name conflicts in the data directory
	 * @param monitor task monitor
	 * @return a one-line result description
	 * @throws IOException on failure
	 * @throws CancelledException if cancelled
	 */
	public String addFile(File file, boolean install, ConflictResolver resolver,
			TaskMonitor monitor) throws IOException, CancelledException {
		String name = file.getName().toLowerCase(Locale.ROOT);
		if (name.endsWith(RAW_EXT)) {
			return installRawFile(file, resolver, monitor);
		}
		if (!name.endsWith(PACKED_EXT)) {
			throw new IOException("Not a FidDb file (expected " + PACKED_EXT + " or " + RAW_EXT +
				"): " + file);
		}
		FidFile fidFile = findByFile(file);
		if (fidFile == null) {
			fidFile = fidFileManager.addUserFidFile(file);
			if (fidFile == null) {
				throw new IOException("Ghidra rejected the database (invalid or incompatible " +
					"FidDb version): " + file);
			}
		}
		if (install) {
			return install(fidFile, resolver, monitor);
		}
		return "Attached " + file.getName();
	}

	/**
	 * Recursively finds and adds every FidDb under {@code dir}.
	 * @return a summary of the results
	 */
	public List<String> addDirectory(File dir, boolean install, ConflictResolver resolver,
			TaskMonitor monitor) throws IOException, CancelledException {
		List<File> files;
		try (Stream<Path> walk = Files.walk(dir.toPath())) {
			files = walk.map(Path::toFile).filter(FidDbService::isFidDbFile).sorted().toList();
		}
		List<String> results = new ArrayList<>();
		if (files.isEmpty()) {
			results.add("No " + PACKED_EXT + " or " + RAW_EXT + " files found in " + dir);
			return results;
		}
		monitor.initialize(files.size(), "Adding FidDbs...");
		for (File f : files) {
			monitor.checkCancelled();
			monitor.setMessage("Adding " + f.getName());
			try {
				results.add(addFile(f, install, resolver, monitor));
			}
			catch (IOException e) {
				results.add("FAILED " + f.getName() + ": " + e.getMessage());
			}
			monitor.incrementProgress(1);
		}
		return results;
	}

	/**
	 * Downloads a FidDb (or a .zip containing FidDbs) from a URL and adds it.
	 * @return a summary of the results
	 */
	public List<String> addFromUrl(String urlText, boolean install, ConflictResolver resolver,
			TaskMonitor monitor) throws IOException, CancelledException {
		URI uri = normalizeUrl(urlText);
		File downloadDir = getSettingsDir("downloads");
		File downloaded = download(uri, downloadDir, monitor);

		List<String> results = new ArrayList<>();
		results.add("Downloaded " + downloaded.getName() + " (" + downloaded.length() + " bytes)");

		List<File> toAdd = new ArrayList<>();
		if (downloaded.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
			toAdd.addAll(extractFidDbs(downloaded, downloadDir, monitor));
			if (toAdd.isEmpty()) {
				results.add("The archive contained no FidDb files");
			}
		}
		else {
			toAdd.add(downloaded);
		}
		for (File f : toAdd) {
			monitor.checkCancelled();
			try {
				results.add(addFile(f, install, resolver, monitor));
			}
			catch (IOException e) {
				results.add("FAILED " + f.getName() + ": " + e.getMessage());
			}
		}
		return results;
	}

	static URI normalizeUrl(String urlText) throws IOException {
		String text = urlText.trim();
		// Allow pasting a GitHub "blob" page link; fetch the raw file instead.
		if (text.matches("https?://github\\.com/[^/]+/[^/]+/blob/.*")) {
			text = text.replaceFirst("/blob/", "/raw/");
		}
		try {
			URI uri = new URI(text);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				throw new IOException("Only http and https URLs are supported");
			}
			return uri;
		}
		catch (URISyntaxException e) {
			throw new IOException("Invalid URL: " + e.getMessage());
		}
	}

	private File download(URI uri, File downloadDir, TaskMonitor monitor)
			throws IOException, CancelledException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.proxy(ProxySelector.getDefault())
				.build();
		HttpRequest request = HttpRequest.newBuilder(uri)
				.header("User-Agent", "Ghidra-FidDbManager")
				.GET()
				.build();

		monitor.setMessage("Connecting to " + uri.getHost());
		HttpResponse<InputStream> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CancelledException();
		}
		if (response.statusCode() / 100 != 2) {
			response.body().close();
			throw new IOException("Server returned HTTP " + response.statusCode() + " for " + uri);
		}

		String fileName = fileNameFor(response);
		File target = uniqueFile(downloadDir, fileName);
		File partial = new File(downloadDir, target.getName() + ".part");
		long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);

		monitor.setMessage("Downloading " + fileName);
		monitor.setIndeterminate(length <= 0);
		if (length > 0) {
			monitor.initialize(length);
		}
		boolean ok = false;
		try (InputStream in = response.body(); OutputStream out = new FileOutputStream(partial)) {
			byte[] buf = new byte[64 * 1024];
			long total = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				monitor.checkCancelled();
				out.write(buf, 0, n);
				total += n;
				if (length > 0) {
					monitor.setProgress(total);
				}
			}
			ok = true;
		}
		finally {
			monitor.setIndeterminate(false);
			if (!ok) {
				partial.delete();
			}
		}
		Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	private static String fileNameFor(HttpResponse<?> response) throws IOException {
		Optional<String> disposition = response.headers().firstValue("Content-Disposition");
		String name = null;
		if (disposition.isPresent()) {
			for (String part : disposition.get().split(";")) {
				part = part.trim();
				if (part.toLowerCase(Locale.ROOT).startsWith("filename=")) {
					name = part.substring("filename=".length()).replace("\"", "").trim();
				}
			}
		}
		if (name == null || name.isEmpty()) {
			String path = response.uri().getPath();
			name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
			name = URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
		}
		name = new File(name).getName(); // strip any path components
		String lower = name.toLowerCase(Locale.ROOT);
		if (!lower.endsWith(PACKED_EXT) && !lower.endsWith(RAW_EXT) && !lower.endsWith(".zip")) {
			throw new IOException("The URL does not point to a " + PACKED_EXT + ", " + RAW_EXT +
				" or .zip file (got \"" + name + "\")");
		}
		return name;
	}

	private static List<File> extractFidDbs(File zip, File destDir, TaskMonitor monitor)
			throws IOException, CancelledException {
		List<File> out = new ArrayList<>();
		try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				monitor.checkCancelled();
				if (entry.isDirectory()) {
					continue;
				}
				// Use only the entry's simple name - prevents writing outside destDir
				String name = new File(entry.getName().replace('\\', '/')).getName();
				String lower = name.toLowerCase(Locale.ROOT);
				if (!lower.endsWith(PACKED_EXT) && !lower.endsWith(RAW_EXT)) {
					continue;
				}
				File target = uniqueFile(destDir, name);
				monitor.setMessage("Extracting " + name);
				Files.copy(zin, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
				out.add(target);
			}
		}
		return out;
	}

	private static File uniqueFile(File dir, String name) {
		File f = new File(dir, name);
		if (!f.exists()) {
			return f;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		String ext = lower.endsWith(RAW_EXT) ? RAW_EXT
				: lower.endsWith(PACKED_EXT) ? PACKED_EXT
						: lower.endsWith(".zip") ? ".zip" : "";
		String base = name.substring(0, name.length() - ext.length());
		for (int i = 1;; i++) {
			f = new File(dir, base + "_" + i + ext);
			if (!f.exists()) {
				return f;
			}
		}
	}

	//==================================================================================================
	// Install / uninstall
	//==================================================================================================

	/**
	 * Installs a user (packed) database by writing a raw {@code .fidbf} copy of it into the
	 * FunctionID data directory. The original packed file is left untouched on disk but is
	 * detached, so the same functions aren't matched twice.
	 */
	public String install(FidFile fidFile, ConflictResolver resolver, TaskMonitor monitor)
			throws IOException, CancelledException {
		if (fidFile.isInstalled()) {
			return fidFile.getName() + " is already installed";
		}
		File target = installTargetFor(fidFile);
		if (target.exists() && !resolver.overwrite(target)) {
			return "Skipped " + fidFile.getName() + " (" + target.getName() + " already installed)";
		}
		FidFile existingInstalled = findByFile(target);

		File temp = new File(target.getParentFile(), target.getName() + ".tmp");
		temp.delete();
		monitor.setMessage("Installing " + fidFile.getName());
		try (FidDB db = fidFile.getFidDB(false)) {
			db.saveRawDatabaseFile(temp, monitor);
		}
		catch (VersionException e) {
			temp.delete();
			throw new IOException("Incompatible FidDb version: " + fidFile.getName());
		}
		catch (IOException | CancelledException e) {
			temp.delete();
			throw e;
		}

		boolean wasActive = fidFile.isActive();
		if (existingInstalled != null) {
			fidFileManager.removeUserFile(existingInstalled); // drop stale entry before replacing
		}
		Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		fidFileManager.removeUserFile(fidFile);

		return registerOrDefer(target, wasActive, "Installed " + fidFile.getName() + " as " +
			target.getName());
	}

	/** Installs a raw {@code .fidbf} file by copying it into the data directory. */
	public String installRawFile(File rawFile, ConflictResolver resolver, TaskMonitor monitor)
			throws IOException {
		File target = installTargetFor(rawFile);
		if (target.getCanonicalFile().equals(rawFile.getCanonicalFile())) {
			FidFile existing = findByFile(target);
			if (existing != null) {
				return rawFile.getName() + " is already installed";
			}
			return registerOrDefer(target, true, "Registered " + rawFile.getName());
		}
		if (target.exists() && !resolver.overwrite(target)) {
			return "Skipped " + rawFile.getName() + " (already installed)";
		}
		FidFile existing = findByFile(target);
		if (existing != null) {
			fidFileManager.removeUserFile(existing);
		}
		monitor.setMessage("Installing " + rawFile.getName());
		Files.copy(rawFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return registerOrDefer(target, existing == null || existing.isActive(),
			"Installed " + rawFile.getName());
	}

	/**
	 * Uninstalls an installed database: it is removed from the active set and the
	 * {@code .fidbf} file is moved to a per-user backup folder (never deleted outright).
	 * @return the backup file
	 */
	public File uninstall(FidFile fidFile) throws IOException {
		if (!fidFile.isInstalled()) {
			throw new IOException(fidFile.getName() + " is not installed");
		}
		File source = fileOf(fidFile);
		File backup = uniqueFile(getSettingsDir("uninstalled"), source.getName());
		fidFileManager.removeUserFile(fidFile);
		try {
			Files.move(source.toPath(), backup.toPath());
		}
		catch (IOException e) {
			registerInstalled(source); // put it back
			throw new IOException("Could not move " + source + " (it may be in use, or the " +
				"Ghidra folder may be read-only): " + e.getMessage(), e);
		}
		return backup;
	}

	/** Detaches a user database (the file itself is not deleted). */
	public void detach(FidFile fidFile) {
		fidFileManager.removeUserFile(fidFile);
	}

	private String registerOrDefer(File installed, boolean active, String message) {
		FidFile registered = registerInstalled(installed);
		if (registered == null) {
			return message + " - restart Ghidra to load it";
		}
		if (registered.isActive() != active) {
			registered.setActive(active);
		}
		return message;
	}

	public FidFile findByFile(File file) {
		File canonical;
		try {
			canonical = file.getCanonicalFile();
		}
		catch (IOException e) {
			canonical = file.getAbsoluteFile();
		}
		for (FidFile f : fidFileManager.getFidFiles()) {
			try {
				if (fileOf(f).getCanonicalFile().equals(canonical)) {
					return f;
				}
			}
			catch (IOException e) {
				// ignore
			}
		}
		return null;
	}

	//==================================================================================================
	// Live registration of installed (raw) files
	//==================================================================================================

	/**
	 * Ghidra only scans for installed {@code .fidbf} files once, at startup, and
	 * {@link FidFileManager} offers no public way to register one later. To make a newly
	 * installed database usable without a restart, this reaches into the manager reflectively.
	 * If that ever fails (e.g. internals change in a future Ghidra), null is returned and the
	 * file is simply picked up on the next restart.
	 *
	 * @param rawFile the installed .fidbf file
	 * @return the registered FidFile, or null if it could not be registered live
	 */
	@SuppressWarnings("unchecked")
	public FidFile registerInstalled(File rawFile) {
		FidFile existing = findByFile(rawFile);
		if (existing != null) {
			return existing;
		}
		try {
			fidFileManager.load();

			Constructor<FidFile> ctor = FidFile.class.getDeclaredConstructor(FidFileManager.class,
				File.class, boolean.class);
			ctor.setAccessible(true);
			FidFile fidFile = ctor.newInstance(fidFileManager, rawFile, true);

			Method isValid = FidFile.class.getDeclaredMethod("isValidFile");
			isValid.setAccessible(true);
			if (!((Boolean) isValid.invoke(fidFile))) {
				return null;
			}

			Field filesField = FidFileManager.class.getDeclaredField("fidFiles");
			filesField.setAccessible(true);
			Set<FidFile> files = (Set<FidFile>) filesField.get(fidFileManager);
			if (files == null) {
				return null;
			}
			files.add(fidFile);

			Method notify = FidFileManager.class.getDeclaredMethod("notifyListeners");
			notify.setAccessible(true);
			notify.invoke(fidFileManager);
			return fidFile;
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			Msg.warn(this, "Could not register " + rawFile + " without a restart: " + e);
			return null;
		}
	}
}
