package com.blur.client;

import com.blur.BlurMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All measurement math for Blur. Pure Java, no Minecraft classes, so it is
 * identical on every game version -- only the detection hooks that feed it
 * (see {@link BlurClient}) are version specific.
 *
 * <p>Every public method is {@code synchronized}: events arrive on the client
 * thread while {@link #snapshotJson()} is read from the dashboard's HTTP threads.
 *
 * <p><b>Persistence:</b> all-time records are updated <em>live</em> as they happen
 * and flushed to disk on a debounce, on session stop, and on game shutdown. That
 * way records survive closing Minecraft (or a crash) without pressing stop first.
 */
public final class StatEngine {

	private static final long WIN_1S = 1000L;
	private static final long WIN_5S = 5000L;
	/** Two opponent totem pops closer than this are treated as one combo/burst. */
	private static final long TOTEM_BURST_GAP = 3000L;
	/** Don't hammer the disk: flush at most this often while playing. */
	private static final long SAVE_DEBOUNCE_MS = 10_000L;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// --- live rolling windows (always tracked, even when not measuring) ---
	private final Deque<Long> crystalTimes = new ArrayDeque<>();
	private final Deque<Long> anchorTimes = new ArrayDeque<>();

	// --- current measured session ---
	private boolean active = false;
	private long sessionStart = 0L;
	private long sessionCrystals = 0L;
	private long sessionAnchors = 0L;
	private long sessionOpponentTotems = 0L;
	private long sessionOwnTotems = 0L;
	private int sessionBestCps1s = 0;
	private int sessionBestCps5s = 0;
	private int sessionBestAnchor1s = 0;
	private int sessionBestAnchor5s = 0;
	private int sessionBiggestTotemBurst = 0;

	// --- opponent totem burst tracking (entityId -> [lastPopTime, burstCount]) ---
	private final Map<Integer, long[]> totemBursts = new HashMap<>();

	// --- who you were fighting: player name -> times seen nearby this session ---
	private final Map<String, Integer> nearbyPlayers = new HashMap<>();

	/** Display name of the measure keybind, e.g. "]" -- shown in the app's UI text. */
	private volatile String keyLabel = "]";

	// --- persisted all-time records ---
	private final AllTime allTime;
	private final Path recordsFile;
	private boolean dirty = false;
	private long lastSave = 0L;

	public StatEngine() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(BlurMod.MOD_ID);
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			Diagnostics.note("Could not create config dir %s: %s", dir, e);
			BlurMod.LOGGER.warn("Could not create config dir", e);
		}
		this.recordsFile = dir.resolve("records.json");
		this.allTime = loadAllTime();
	}

	// ----------------------------------------------------------------- events

	/** Called when we have confirmed the local player placed an end crystal. */
	public synchronized void recordCrystal() {
		long now = System.currentTimeMillis();
		crystalTimes.addLast(now);
		prune(crystalTimes, now);
		if (!active) return;
		sessionCrystals++;
		sessionBestCps1s = Math.max(sessionBestCps1s, countWithin(crystalTimes, now, WIN_1S));
		sessionBestCps5s = Math.max(sessionBestCps5s, countWithin(crystalTimes, now, WIN_5S));
		allTime.totalCrystals++;
		allTime.bestCps1s = Math.max(allTime.bestCps1s, sessionBestCps1s);
		allTime.bestCps5s = Math.max(allTime.bestCps5s, sessionBestCps5s);
		dirty = true;
	}

	/** Called on each interaction with a respawn anchor (charge or detonate). */
	public synchronized void recordAnchor() {
		long now = System.currentTimeMillis();
		anchorTimes.addLast(now);
		prune(anchorTimes, now);
		if (!active) return;
		sessionAnchors++;
		sessionBestAnchor1s = Math.max(sessionBestAnchor1s, countWithin(anchorTimes, now, WIN_1S));
		sessionBestAnchor5s = Math.max(sessionBestAnchor5s, countWithin(anchorTimes, now, WIN_5S));
		allTime.totalAnchors++;
		allTime.bestAnchor1s = Math.max(allTime.bestAnchor1s, sessionBestAnchor1s);
		allTime.bestAnchor5s = Math.max(allTime.bestAnchor5s, sessionBestAnchor5s);
		dirty = true;
	}

	/** Called when a nearby opponent pops a totem (i.e. you forced it). */
	public synchronized void recordOpponentTotem(int entityId) {
		long now = System.currentTimeMillis();
		long[] state = totemBursts.get(entityId);
		int burst = (state != null && now - state[0] <= TOTEM_BURST_GAP) ? (int) state[1] + 1 : 1;
		totemBursts.put(entityId, new long[]{now, burst});
		if (!active) return;
		sessionOpponentTotems++;
		sessionBiggestTotemBurst = Math.max(sessionBiggestTotemBurst, burst);
		allTime.totalTotems++;
		allTime.biggestTotemBurst = Math.max(allTime.biggestTotemBurst, sessionBiggestTotemBurst);
		dirty = true;
	}

	/**
	 * Called periodically with each player currently within ~2 chunks of you.
	 * Sightings are counted so a passer-by doesn't outrank the person you actually
	 * fought; {@link #opponentNames()} reports the most-seen player(s).
	 */
	public synchronized void noteNearbyPlayer(String name) {
		if (!active || name == null || name.isEmpty()) return;
		nearbyPlayers.merge(name, 1, Integer::sum);
	}

	/** One name if only one player was ever around, otherwise the two most-seen. */
	private List<String> opponentNames() {
		return nearbyPlayers.entrySet().stream()
				.sorted((a, b) -> b.getValue() - a.getValue())
				.limit(nearbyPlayers.size() <= 1 ? 1 : 2)
				.map(Map.Entry::getKey)
				.toList();
	}

	/** Called when YOU pop a totem. Paired with opponent totems this is the
	 *  forced-vs-used ratio, the clearest read on who actually won the fight. */
	public synchronized void recordOwnTotem() {
		if (!active) return;
		sessionOwnTotems++;
		allTime.totalOwnTotems++;
		dirty = true;
	}

	// --------------------------------------------------------------- session

	/** @return true if measuring is now ON, false if now OFF. */
	public synchronized boolean toggleSession() {
		if (active) {
			active = false;
			save();
		} else {
			active = true;
			sessionStart = System.currentTimeMillis();
			sessionCrystals = 0;
			sessionAnchors = 0;
			sessionOpponentTotems = 0;
			sessionOwnTotems = 0;
			sessionBestCps1s = 0;
			sessionBestCps5s = 0;
			sessionBestAnchor1s = 0;
			sessionBestAnchor5s = 0;
			sessionBiggestTotemBurst = 0;
			totemBursts.clear();
			nearbyPlayers.clear();
			allTime.sessionsPlayed++;
			dirty = true;
		}
		return active;
	}

	public synchronized boolean isActive() {
		return active;
	}

	/** Keeps the app's on-screen hints correct if the player rebinds the key. */
	public void setKeyLabel(String label) {
		if (label != null && !label.isEmpty()) this.keyLabel = label;
	}

	/** Called every client tick; flushes to disk on a debounce. */
	public synchronized void tickPersist() {
		if (!dirty) return;
		long now = System.currentTimeMillis();
		if (now - lastSave >= SAVE_DEBOUNCE_MS) save();
	}

	/** Called when the game is closing so nothing is lost. */
	public synchronized void shutdown() {
		active = false;
		if (dirty) save();
	}

	// -------------------------------------------------------------- snapshot

	/** Builds the JSON the dashboard renders. Recomputed live so meters decay. */
	public synchronized String snapshotJson() {
		long now = System.currentTimeMillis();
		prune(crystalTimes, now);
		prune(anchorTimes, now);

		JsonObject live = new JsonObject();
		live.addProperty("cps1s", countWithin(crystalTimes, now, WIN_1S));
		live.addProperty("cps5s", countWithin(crystalTimes, now, WIN_5S));
		live.addProperty("anchor1s", countWithin(anchorTimes, now, WIN_1S));
		live.addProperty("anchor5s", countWithin(anchorTimes, now, WIN_5S));

		JsonObject session = new JsonObject();
		session.addProperty("active", active);
		session.addProperty("durationMs", active ? now - sessionStart : 0L);
		session.addProperty("crystals", sessionCrystals);
		session.addProperty("bestCps1s", sessionBestCps1s);
		session.addProperty("bestCps5s", sessionBestCps5s);
		session.addProperty("anchors", sessionAnchors);
		session.addProperty("bestAnchor1s", sessionBestAnchor1s);
		session.addProperty("bestAnchor5s", sessionBestAnchor5s);
		session.addProperty("opponentTotems", sessionOpponentTotems);
		session.addProperty("ownTotems", sessionOwnTotems);
		session.addProperty("biggestTotemBurst", sessionBiggestTotemBurst);
		JsonArray opps = new JsonArray();
		for (String n : opponentNames()) opps.add(n);
		session.add("opponents", opps);

		JsonObject at = new JsonObject();
		at.addProperty("bestCps1s", allTime.bestCps1s);
		at.addProperty("bestCps5s", allTime.bestCps5s);
		at.addProperty("bestAnchor1s", allTime.bestAnchor1s);
		at.addProperty("bestAnchor5s", allTime.bestAnchor5s);
		at.addProperty("biggestTotemBurst", allTime.biggestTotemBurst);
		at.addProperty("totalCrystals", allTime.totalCrystals);
		at.addProperty("totalAnchors", allTime.totalAnchors);
		at.addProperty("totalTotems", allTime.totalTotems);
		at.addProperty("totalOwnTotems", allTime.totalOwnTotems);
		at.addProperty("sessionsPlayed", allTime.sessionsPlayed);

		JsonObject root = new JsonObject();
		root.addProperty("ts", now);
		root.addProperty("keybind", keyLabel);
		root.add("live", live);
		root.add("session", session);
		root.add("allTime", at);
		return root.toString();
	}

	/** Plain-text block used by the BlurLog support report. */
	public synchronized String describeForLog() {
		StringBuilder sb = new StringBuilder();
		sb.append("Measuring right now : ").append(active ? "YES" : "no").append('\n');
		sb.append("Session crystals    : ").append(sessionCrystals).append('\n');
		sb.append("Session anchors     : ").append(sessionAnchors).append('\n');
		sb.append("Session totems      : ").append(sessionOpponentTotems)
				.append(" forced / ").append(sessionOwnTotems).append(" used\n");
		sb.append('\n');
		sb.append("ALL-TIME RECORDS\n");
		sb.append("  best crystal /1s  : ").append(allTime.bestCps1s).append('\n');
		sb.append("  best crystal /5s  : ").append(allTime.bestCps5s).append('\n');
		sb.append("  best anchor  /1s  : ").append(allTime.bestAnchor1s).append('\n');
		sb.append("  best anchor  /5s  : ").append(allTime.bestAnchor5s).append('\n');
		sb.append("  biggest totem combo: ").append(allTime.biggestTotemBurst).append('\n');
		sb.append("  total crystals    : ").append(allTime.totalCrystals).append('\n');
		sb.append("  total anchors     : ").append(allTime.totalAnchors).append('\n');
		sb.append("  total totems forced: ").append(allTime.totalTotems).append('\n');
		sb.append("  sessions played   : ").append(allTime.sessionsPlayed).append('\n');
		sb.append('\n');
		sb.append("Records file        : ").append(recordsFile).append('\n');
		sb.append("Records file exists : ").append(Files.exists(recordsFile) ? "yes" : "NO (nothing saved yet)").append('\n');
		sb.append("Records writable    : ").append(Files.isWritable(recordsFile.getParent()) ? "yes" : "NO  <-- PROBLEM").append('\n');
		sb.append("Unsaved changes     : ").append(dirty ? "yes" : "no").append('\n');
		return sb.toString();
	}

	// --------------------------------------------------------------- helpers

	private static void prune(Deque<Long> q, long now) {
		while (!q.isEmpty() && now - q.peekFirst() > WIN_5S) {
			q.pollFirst();
		}
	}

	private static int countWithin(Deque<Long> q, long now, long window) {
		int c = 0;
		for (long t : q) {
			if (now - t <= window) c++;
		}
		return c;
	}

	// --------------------------------------------------------- persistence

	private AllTime loadAllTime() {
		if (Files.exists(recordsFile)) {
			try (Reader r = Files.newBufferedReader(recordsFile)) {
				AllTime a = GSON.fromJson(r, AllTime.class);
				if (a != null) return a;
				Diagnostics.note("records.json was empty/unreadable, starting fresh");
			} catch (Exception e) {
				Diagnostics.note("Could not read records.json: %s", e);
				BlurMod.LOGGER.warn("Could not read records.json, starting fresh", e);
			}
		}
		return new AllTime();
	}

	private void save() {
		try (Writer w = Files.newBufferedWriter(recordsFile)) {
			GSON.toJson(allTime, w);
			dirty = false;
			lastSave = System.currentTimeMillis();
		} catch (IOException e) {
			Diagnostics.note("FAILED to save records.json: %s", e);
			BlurMod.LOGGER.warn("Could not save records.json", e);
		}
	}

	/** Plain data holder persisted to records.json. */
	private static final class AllTime {
		int bestCps1s = 0;
		int bestCps5s = 0;
		int bestAnchor1s = 0;
		int bestAnchor5s = 0;
		int biggestTotemBurst = 0;
		long totalCrystals = 0;
		long totalAnchors = 0;
		long totalTotems = 0;      // totems you forced out of opponents
		long totalOwnTotems = 0;   // totems you burned yourself
		long sessionsPlayed = 0;
	}
}
