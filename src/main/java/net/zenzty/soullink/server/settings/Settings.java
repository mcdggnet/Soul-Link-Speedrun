package net.zenzty.soullink.server.settings;

import net.minecraft.world.Difficulty;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;

/**
 * Holds all configurable settings for the Soul Link mod. Settings are applied on the next run when
 * confirmed in the settings GUI.
 */
public class Settings {

    private static final Settings INSTANCE = new Settings();

    // Current active settings (used during runs)
    private Difficulty difficulty = Difficulty.NORMAL;
    private boolean sharedHealth = true; // Off = everyone keeps their own health/hunger; deaths still shared
    private boolean halfHeartMode = false;
    private boolean sharedPotions = false;
    private boolean sharedJumping = false;
    private boolean manhuntMode = false;
    private boolean syncedInventory = false;
    private boolean worldReset = true; // Fresh world each run; death ends the run
    private boolean serverMode = false; // Always-on mechanics in the normal worlds, no runs
    private boolean damageLogEnabled = true; // Combat log - can be toggled immediately
    private boolean timerHudEnabled = true; // Action bar timer - can be toggled immediately
    private boolean hardcoreHearts = false; // Hardcore-style hearts on clients - applies on (re)join
    private boolean joinMessagesEnabled = true; // Welcome / how-to chat text on join - immediate

    // Pending settings to be applied on next run
    private SettingsSnapshot pendingSnapshot = null;

    private Settings() {}

    public static Settings getInstance() {
        return INSTANCE;
    }

    // ==================== DIFFICULTY ====================

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        // Normalize Peaceful to Easy since mod doesn't support it
        this.difficulty = (difficulty == Difficulty.PEACEFUL) ? Difficulty.EASY : difficulty;
    }

    /**
     * Cycles to the next difficulty level. Order: EASY -> NORMAL -> HARD -> EASY (no Peaceful)
     */
    public Difficulty getNextDifficulty() {
        return switch (difficulty) {
            case PEACEFUL, EASY -> Difficulty.NORMAL;
            case NORMAL -> Difficulty.HARD;
            case HARD -> Difficulty.EASY;
        };
    }

    // ==================== SHARED HEALTH ====================

    /**
     * Whether health, hunger, saturation and absorption are one pool for the whole group (the
     * classic Soul Link). Off means everyone has their own bars and only death is linked: when
     * anyone dies, everyone does, handled exactly as before (run over, item-pile group death or
     * world reset, per World Reset / Server Mode). Shared potions and jumps are separate settings.
     */
    public boolean isSharedHealth() {
        return sharedHealth;
    }

    public void setSharedHealth(boolean sharedHealth) {
        this.sharedHealth = sharedHealth;
    }

    // ==================== HALF HEART MODE ====================

    public boolean isHalfHeartMode() {
        return halfHeartMode;
    }

    public void setHalfHeartMode(boolean halfHeartMode) {
        this.halfHeartMode = halfHeartMode;
    }

    // ==================== SHARED POTIONS ====================

    public boolean isSharedPotions() {
        return sharedPotions;
    }

    public void setSharedPotions(boolean sharedPotions) {
        this.sharedPotions = sharedPotions;
    }

    // ==================== SHARED JUMPING ====================

    public boolean isSharedJumping() {
        return sharedJumping;
    }

    public void setSharedJumping(boolean sharedJumping) {
        this.sharedJumping = sharedJumping;
    }

    // ==================== MANHUNT MODE ====================

    /**
     * Whether Manhunt mode is enabled. When true, a Runner/Hunter selector is shown before the run.
     * Runners share Soul Link (health, hunger); Hunters use vanilla mechanics and get tracking
     * compasses.
     */
    public boolean isManhuntMode() {
        // Manhunt is a run format; there are no runs in Server Mode.
        return manhuntMode && !serverMode;
    }

    /** The stored Manhunt value regardless of Server Mode, for the menu and the save file. */
    public boolean isManhuntModeSetting() {
        return manhuntMode;
    }

    /**
     * Whether Manhunt will be enabled for the next run. If the player confirmed changes in /chaos
     * during an active run, those are pending and this returns the pending Manhunt value; otherwise
     * the current setting. Use this when deciding to open the Runner/Hunter selector before
     * startRun, because applyPendingSettings runs inside startRun.
     */
    public boolean isManhuntModeForNextRun() {
        return pendingSnapshot != null ? pendingSnapshot.manhuntMode() : manhuntMode;
    }

    public void setManhuntMode(boolean manhuntMode) {
        this.manhuntMode = manhuntMode;
    }

    // ==================== SYNCED INVENTORY ====================

    /**
     * Whether Synced Inventory mode is enabled. When true, all players in the run share the same
     * inventory (main, hotbar, armor, offhand); changes by one player appear for everyone.
     */
    public boolean isSyncedInventory() {
        return syncedInventory;
    }

    public void setSyncedInventory(boolean syncedInventory) {
        this.syncedInventory = syncedInventory;
    }

    // ==================== WORLD RESET ====================

    /**
     * Whether a run lives in a throwaway world. On (the classic mode): any death ends the run for
     * everyone and the next /start builds a brand new world. Off: a death kills the whole group at
     * once, everything they carried lands in one pile where the victim fell, everyone respawns at the
     * run spawn and the run carries on in the same world; /start after a victory or /stoprun restarts
     * in that same world too. Applies on the next run like the other run settings.
     */
    public boolean isWorldReset() {
        return worldReset;
    }

    public void setWorldReset(boolean worldReset) {
        this.worldReset = worldReset;
    }

    // ==================== SERVER MODE ====================

    /**
     * Whether Soul Link runs as an always-on server instead of a speedrun. On: the mod keeps one
     * persistent world of its own (overworld, nether, end) that survives restarts, everyone plays in
     * it with the shared mechanics all the time, and there is no /start, no timer and no victory
     * screen. World Reset still decides what a death does: on, everyone dies and a fresh world is
     * generated; off, everyone dies, drops their items in one pile and respawns in the same world.
     * Switched from /settings when no speedrun is active; RunManager handles the transition.
     */
    public boolean isServerMode() {
        return serverMode;
    }

    public void setServerMode(boolean serverMode) {
        this.serverMode = serverMode;
    }

    // ==================== DAMAGE LOG ====================

    public boolean isDamageLogEnabled() {
        return damageLogEnabled;
    }

    public void setDamageLogEnabled(boolean damageLogEnabled) {
        this.damageLogEnabled = damageLogEnabled;
    }

    // ==================== TIMER HUD ====================

    /**
     * Whether the run timer is shown in the action bar. The timer itself always runs; this only
     * controls the HUD text ("00:00:00 - Move to start" and the elapsed time). /runinfo and the
     * final time are unaffected.
     */
    public boolean isTimerHudEnabled() {
        return timerHudEnabled;
    }

    public void setTimerHudEnabled(boolean timerHudEnabled) {
        this.timerHudEnabled = timerHudEnabled;
    }

    // ==================== HARDCORE HEARTS ====================

    /**
     * Whether clients are told the world is hardcore, which makes them draw the hardcore heart
     * texture. Nothing else about hardcore applies: the world is not hardcore and death is handled
     * by the run as usual. The flag travels in the login packet, so it takes effect for each player
     * when they next join.
     */
    public boolean isHardcoreHearts() {
        return hardcoreHearts;
    }

    public void setHardcoreHearts(boolean hardcoreHearts) {
        this.hardcoreHearts = hardcoreHearts;
    }

    // ==================== JOIN MESSAGES ====================

    /**
     * Whether a joining player gets the welcome text (what Soul Link is, /start, the /settings tip)
     * and the "run has ended" notice. Off for servers whose players already know the drill; the
     * action bar still shows the /start hint between runs either way.
     */
    public boolean isJoinMessagesEnabled() {
        return joinMessagesEnabled;
    }

    public void setJoinMessagesEnabled(boolean joinMessagesEnabled) {
        this.joinMessagesEnabled = joinMessagesEnabled;
    }

    // ==================== UTILITY ====================

    /**
     * Returns the pending snapshot if one exists (changes confirmed in /settings during an active
     * run). Used by the settings menu to pre-fill with pending values so the player sees what is
     * already queued instead of the in-memory (current-run) values.
     */
    public SettingsSnapshot getPendingSnapshotOrNull() {
        return pendingSnapshot;
    }

    /**
     * Creates a copy of the current settings for temporary editing in the GUI.
     */
    public SettingsSnapshot createSnapshot() {
        return new SettingsSnapshot(
                difficulty,
                halfHeartMode,
                sharedPotions,
                sharedJumping,
                manhuntMode,
                syncedInventory,
                worldReset,
                sharedHealth);
    }

    /**
     * Applies settings from a snapshot. If a run is active, all changes except difficulty are
     * deferred until the next run.
     */
    public void applySnapshot(SettingsSnapshot snapshot) {
        // Check if a run is active
        // Server Mode has no "next run": its world is always running, so changes apply now.
        RunManager runManager = RunManager.getInstance();
        boolean runActive = !serverMode
                && runManager != null
                && (runManager.getGameState() == RunState.RUNNING
                        || runManager.getGameState() == RunState.GENERATING_WORLD);

        if (runActive) {
            // Already queued this exact snapshot (e.g. re-confirm without changing) – no-op
            if (pendingSnapshot != null && snapshot.equals(pendingSnapshot)) {
                return;
            }
            // User reverted to the current applied state – clear pending
            SettingsSnapshot current = createSnapshot();
            if (snapshot.equals(current)) {
                this.pendingSnapshot = null;
                return;
            }

            // Defer all changes until next run
            this.pendingSnapshot = snapshot;
            SoulLink.LOGGER.info("Settings changes queued for next run: {}", snapshot);
        } else {
            // No active run - apply immediately
            applySnapshotInternal(snapshot);
            this.pendingSnapshot = null;
        }
    }

    /**
     * Internal method to apply all settings from a snapshot immediately.
     */
    private void applySnapshotInternal(SettingsSnapshot snapshot) {
        setDifficulty(snapshot.difficulty());
        this.halfHeartMode = snapshot.halfHeartMode();
        this.sharedPotions = snapshot.sharedPotions();
        this.sharedJumping = snapshot.sharedJumping();
        this.manhuntMode = snapshot.manhuntMode();
        this.syncedInventory = snapshot.syncedInventory();
        this.worldReset = snapshot.worldReset();
        this.sharedHealth = snapshot.sharedHealth();

        SoulLink.LOGGER.info(
                "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={}, Manhunt={}, SyncedInventory={}, WorldReset={}, SharedHealth={}",
                difficulty,
                halfHeartMode,
                sharedPotions,
                sharedJumping,
                manhuntMode,
                syncedInventory,
                worldReset,
                sharedHealth);
    }

    /**
     * Applies any pending settings. Called when a new run starts.
     */
    public void applyPendingSettings() {
        if (pendingSnapshot != null) {
            SoulLink.LOGGER.info("Applying pending settings for new run...");
            applySnapshotInternal(pendingSnapshot);
            pendingSnapshot = null;
        }
    }

    /**
     * Immutable snapshot of settings for comparison and temporary editing.
     */
    public record SettingsSnapshot(
            Difficulty difficulty,
            boolean halfHeartMode,
            boolean sharedPotions,
            boolean sharedJumping,
            boolean manhuntMode,
            boolean syncedInventory,
            boolean worldReset,
            boolean sharedHealth) {}
}
