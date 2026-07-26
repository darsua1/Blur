package com.blur.client;

import com.blur.BlurMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects anything that went wrong at runtime and writes a human-readable
 * support report to {@code .minecraft/logs/BlurLog.txt} when the user presses the
 * dashboard keybind. Designed so a Discord moderator can skim it and immediately
 * see what is broken, without knowing anything about the codebase.
 */
public final class Diagnostics {

	private static final int MAX_NOTES = 60;
	private static final Deque<String> NOTES = new ArrayDeque<>();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// Totem-detection instrumentation (set from LivingEntityMixin), so the log can
	// prove whether the mixin fires and whether the server sends totem-pop events.
	public static final AtomicLong entityEvents = new AtomicLong(); // any entity event handled
	public static final AtomicLong totemEvents = new AtomicLong();  // entity event id 35 (totem)

	private Diagnostics() {
	}

	/** Called from the mixin on every entity event -> proves the mixin is active. */
	public static void entityEventSeen() {
		entityEvents.incrementAndGet();
	}

	/** Called from the mixin whenever a totem-pop (id 35) is received from anyone. */
	public static void totemEventSeen() {
		totemEvents.incrementAndGet();
	}

	/** Record a notable problem. Safe to call from any thread. */
	public static void note(String fmt, Object... args) {
		String line = LocalDateTime.now().format(STAMP) + "  " + String.format(fmt, args);
		synchronized (NOTES) {
			NOTES.addLast(line);
			while (NOTES.size() > MAX_NOTES) NOTES.pollFirst();
		}
	}

	/**
	 * Writes the report.
	 *
	 * @return the file it wrote, or null if writing failed.
	 */
	public static Path writeReport(StatEngine engine, WebServer server) {
		Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
		Path out = logs.resolve("BlurLog.txt");
		StringBuilder sb = new StringBuilder();

		sb.append("=======================================================\n");
		sb.append("  BLUR - SUPPORT REPORT\n");
		sb.append("  Generated: ").append(LocalDateTime.now().format(FULL)).append('\n');
		sb.append("  Send this whole file in your Discord ticket.\n");
		sb.append("=======================================================\n\n");

		// ---- quick verdict so a moderator sees the problem immediately ----
		sb.append("QUICK CHECK\n");
		sb.append("-----------\n");
		boolean serverUp = server != null && server.isRunning();
		boolean appConnected = server != null && server.getClientCount() > 0;
		sb.append(line("Mod loaded", true, "yes", "no"));
		sb.append(line("Dashboard server running", serverUp, "yes (port " + (server != null ? server.getPort() : -1) + ")",
				"NO  <-- the app can never connect"));
		sb.append(line("Blur app connected", appConnected,
				"yes (" + (server != null ? server.getClientCount() : 0) + " connected)",
				"no  <-- open Blur.exe, or a firewall is blocking localhost"));
		long ee = entityEvents.get(), te = totemEvents.get();
		sb.append(line("Opponent totem pops", te > 0,
				te + " received from the server",
				ee > 0 ? "0  <-- server is NOT sending totem-pop events (likely anticheat)"
						: "0  (no combat recorded yet, or the mixin never fired)"));
		synchronized (NOTES) {
			sb.append(line("Errors recorded", NOTES.isEmpty(), "none", NOTES.size() + "  <-- see PROBLEMS below"));
		}
		sb.append('\n');

		// ---- environment ----
		sb.append("ENVIRONMENT\n");
		sb.append("-----------\n");
		sb.append("Blur version        : ").append(modVersion(BlurMod.MOD_ID)).append('\n');
		sb.append("Minecraft           : ").append(modVersion("minecraft")).append('\n');
		sb.append("Fabric Loader       : ").append(modVersion("fabricloader")).append('\n');
		sb.append("Fabric API          : ").append(modVersion("fabric-api")).append('\n');
		sb.append("Java                : ").append(System.getProperty("java.version"))
				.append("  (").append(System.getProperty("java.vendor")).append(")\n");
		sb.append("Operating system    : ").append(System.getProperty("os.name"))
				.append(' ').append(System.getProperty("os.version"))
				.append(" (").append(System.getProperty("os.arch")).append(")\n");
		sb.append("Game folder         : ").append(gameDir()).append('\n');
		sb.append("Total mods loaded   : ").append(FabricLoader.getInstance().getAllMods().size()).append('\n');
		sb.append('\n');

		// ---- dashboard ----
		sb.append("DASHBOARD CONNECTION\n");
		sb.append("--------------------\n");
		if (server == null) {
			sb.append("Server object missing (mod failed to start properly).\n");
		} else {
			sb.append("Running             : ").append(server.isRunning() ? "yes" : "NO").append('\n');
			sb.append("Port                : ").append(server.getPort() > 0 ? server.getPort() : "none bound").append('\n');
			sb.append("URL                 : ").append(server.getUrl() == null ? "n/a" : server.getUrl()).append('\n');
			sb.append("Apps connected now  : ").append(server.getClientCount()).append('\n');
		}
		sb.append('\n');

		// ---- stats ----
		sb.append("STATS & SAVING\n");
		sb.append("--------------\n");
		sb.append(engine == null ? "Stat engine missing.\n" : engine.describeForLog());
		sb.append("Entity events seen  : ").append(entityEvents.get())
				.append("   (of those, totem id-35 events: ").append(totemEvents.get()).append(")\n");
		sb.append("  If entity events > 0 but totem events = 0, the server/anticheat\n");
		sb.append("  is not broadcasting opponent totem pops -- nothing Blur can do client-side.\n");
		sb.append('\n');

		// ---- other mods (conflict hunting) ----
		sb.append("OTHER MODS INSTALLED\n");
		sb.append("--------------------\n");
		List<String> mods = FabricLoader.getInstance().getAllMods().stream()
				.map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString())
				.filter(s -> !s.startsWith("fabric-")) // hide the ~50 fabric api submodules
				.sorted()
				.toList();
		for (String m : mods) sb.append("  ").append(m).append('\n');
		sb.append('\n');

		// ---- problems ----
		sb.append("PROBLEMS RECORDED THIS SESSION\n");
		sb.append("------------------------------\n");
		synchronized (NOTES) {
			if (NOTES.isEmpty()) {
				sb.append("  (none - Blur did not hit any errors)\n");
			} else {
				for (String n : NOTES) sb.append("  ").append(n).append('\n');
			}
		}
		sb.append("\n=================== end of report =====================\n");

		try {
			Files.createDirectories(logs);
			Files.writeString(out, sb.toString());
			return out;
		} catch (IOException e) {
			BlurMod.LOGGER.warn("Could not write BlurLog", e);
			note("Could not write BlurLog: %s", e);
			return null;
		}
	}

	private static String line(String label, boolean ok, String yes, String no) {
		return String.format("[%s] %-26s %s%n", ok ? "OK" : "!!", label, ok ? yes : no);
	}

	private static String modVersion(String id) {
		Optional<ModContainer> c = FabricLoader.getInstance().getModContainer(id);
		return c.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("not found");
	}

	private static String gameDir() {
		try {
			return FabricLoader.getInstance().getGameDir().toString();
		} catch (Exception e) {
			return "unknown";
		}
	}
}
