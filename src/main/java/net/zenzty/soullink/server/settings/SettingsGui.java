package net.zenzty.soullink.server.settings;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.zenzty.soullink.mixin.ui.ScreenHandlerAccessor;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;

/**
 * The one settings menu (/settings): a virtual double chest with every Soul Link option. There is
 * no save button; closing the menu applies whatever was changed. Run-scoped options (difficulty,
 * half heart, shared potions/jumping, manhunt, synced inventory, world reset) apply immediately when
 * no run is active and are queued for the next run otherwise; the HUD/chat toggles always apply at
 * once; Server Mode switches the whole mod between speedrun runs and always-on mechanics.
 */
public class SettingsGui {

    // Row 1: run-scoped options
    private static final int DIFFICULTY_SLOT = 9;
    private static final int SHARED_HEALTH_SLOT = 11;
    private static final int HALF_HEART_SLOT = 13;
    private static final int SHARED_POTIONS_SLOT = 15;
    private static final int SHARED_JUMPING_SLOT = 17;
    // Row 3: modes
    private static final int MANHUNT_SLOT = 28;
    private static final int SYNCED_INVENTORY_SLOT = 30;
    private static final int WORLD_RESET_SLOT = 32;
    private static final int SERVER_MODE_SLOT = 34;
    // Row 5: HUD and chat toggles
    private static final int COMBAT_LOG_SLOT = 46;
    private static final int TIMER_HUD_SLOT = 48;
    private static final int HARDCORE_HEARTS_SLOT = 50;
    private static final int JOIN_MESSAGES_SLOT = 52;
    // Top right: command list
    private static final int COMMANDS_SLOT = 8;

    private static final int INVENTORY_SIZE = 54;

    /**
     * Opens the settings menu. Spectators can look but the client will not let them click.
     */
    public static void open(ServerPlayer player) {
        Settings settings = Settings.getInstance();
        // Show queued changes if there are any (confirmed during a run), else the live values.
        Settings.SettingsSnapshot original = settings.getPendingSnapshotOrNull();
        if (original == null) {
            original = settings.createSnapshot();
        }

        SettingsInventory inventory = new SettingsInventory(original);
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, playerEntity) -> new SettingsScreenHandler(syncId, inventory, player),
                Component.literal("Soul Link Settings").withStyle(ChatFormatting.DARK_GRAY)));
    }

    private static Component createItemName(String text, ChatFormatting... formattings) {
        Style style = Style.EMPTY.withItalic(false);
        for (ChatFormatting formatting : formattings) {
            style = style.applyFormat(formatting);
        }
        return Component.literal(text).setStyle(style);
    }

    private static MutableComponent plain(String text, ChatFormatting color) {
        return Component.literal(text).setStyle(Style.EMPTY.withItalic(false).applyFormat(color));
    }

    private static MutableComponent statusLine(boolean enabled) {
        return plain("Status: ", ChatFormatting.GRAY)
                .append(enabled ? plain("ENABLED", ChatFormatting.GREEN) : plain("DISABLED", ChatFormatting.RED));
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    static String getDifficultyName(Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> "Peaceful";
            case EASY -> "Easy";
            case NORMAL -> "Normal";
            case HARD -> "Hard";
        };
    }

    /**
     * Virtual inventory holding the values being edited. Server-side only.
     */
    public static class SettingsInventory extends SimpleContainer {

        private final Settings.SettingsSnapshot original;
        private final boolean originalServerMode;
        private final boolean originalDamageLog;
        private final boolean originalTimerHud;
        private final boolean originalHardcoreHearts;
        private final boolean originalJoinMessages;

        private Difficulty pendingDifficulty;
        private boolean pendingSharedHealth;
        private boolean pendingHalfHeart;
        private boolean pendingSharedPotions;
        private boolean pendingSharedJumping;
        private boolean pendingManhunt;
        private boolean pendingSyncedInventory;
        private boolean pendingWorldReset;
        private boolean pendingServerMode;
        private boolean pendingDamageLog;
        private boolean pendingTimerHud;
        private boolean pendingHardcoreHearts;
        private boolean pendingJoinMessages;

        public SettingsInventory(Settings.SettingsSnapshot original) {
            super(INVENTORY_SIZE);
            Settings settings = Settings.getInstance();
            this.original = original;
            this.originalServerMode = settings.isServerMode();
            this.originalDamageLog = settings.isDamageLogEnabled();
            this.originalTimerHud = settings.isTimerHudEnabled();
            this.originalHardcoreHearts = settings.isHardcoreHearts();
            this.originalJoinMessages = settings.isJoinMessagesEnabled();

            this.pendingDifficulty = original.difficulty();
            this.pendingSharedHealth = original.sharedHealth();
            this.pendingHalfHeart = original.halfHeartMode();
            this.pendingSharedPotions = original.sharedPotions();
            this.pendingSharedJumping = original.sharedJumping();
            this.pendingManhunt = original.manhuntMode();
            this.pendingSyncedInventory = original.syncedInventory();
            this.pendingWorldReset = original.worldReset();
            this.pendingServerMode = originalServerMode;
            this.pendingDamageLog = originalDamageLog;
            this.pendingTimerHud = originalTimerHud;
            this.pendingHardcoreHearts = originalHardcoreHearts;
            this.pendingJoinMessages = originalJoinMessages;

            populateItems();
        }

        public void populateItems() {
            ItemStack filler = new ItemStack(Items.STAINED_GLASS_PANE.gray());
            filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                setItem(i, filler.copy());
            }

            setItem(DIFFICULTY_SLOT, createDifficultyItem());
            setItem(SHARED_HEALTH_SLOT, createSharedHealthItem());
            setItem(HALF_HEART_SLOT, createHalfHeartItem());
            setItem(SHARED_POTIONS_SLOT, createSharedPotionsItem());
            setItem(SHARED_JUMPING_SLOT, createSharedJumpingItem());
            setItem(MANHUNT_SLOT, createManhuntItem());
            setItem(SYNCED_INVENTORY_SLOT, createSyncedInventoryItem());
            setItem(WORLD_RESET_SLOT, createWorldResetItem());
            setItem(SERVER_MODE_SLOT, createServerModeItem());
            setItem(COMBAT_LOG_SLOT, createCombatLogItem());
            setItem(TIMER_HUD_SLOT, createTimerHudItem());
            setItem(HARDCORE_HEARTS_SLOT, createHardcoreHeartsItem());
            setItem(JOIN_MESSAGES_SLOT, createJoinMessagesItem());
            setItem(COMMANDS_SLOT, createCommandsItem());
        }

        private ItemStack toggleItem(
                ItemStack item, String name, ChatFormatting nameColor, boolean enabled, List<Component> description) {
            item.set(DataComponents.CUSTOM_NAME, createItemName(name, nameColor, ChatFormatting.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(statusLine(enabled));
            lore.add(Component.empty());
            lore.addAll(description);
            lore.add(Component.empty());
            lore.add(plain("Click to toggle", ChatFormatting.DARK_GRAY));
            item.set(DataComponents.LORE, new ItemLore(lore));
            return item;
        }

        private List<Component> describe(String... lines) {
            List<Component> out = new ArrayList<>();
            for (String line : lines) {
                out.add(plain(line, ChatFormatting.DARK_GRAY));
            }
            return out;
        }

        private List<Component> describeWithServerModeNote(String... lines) {
            List<Component> out = describe(lines);
            if (pendingServerMode) {
                out.add(plain("No effect in Server Mode.", ChatFormatting.YELLOW));
            }
            return out;
        }

        private ItemStack createDifficultyItem() {
            ItemStack item = new ItemStack(
                    switch (pendingDifficulty) {
                        case EASY -> Items.WOODEN_SWORD;
                        case NORMAL -> Items.IRON_SWORD;
                        case HARD -> Items.DIAMOND_SWORD;
                        default -> Items.WOODEN_SWORD;
                    });
            ChatFormatting color =
                    switch (pendingDifficulty) {
                        case EASY -> ChatFormatting.GREEN;
                        case NORMAL -> ChatFormatting.YELLOW;
                        case HARD -> ChatFormatting.RED;
                        default -> ChatFormatting.WHITE;
                    };
            item.set(
                    DataComponents.CUSTOM_NAME,
                    createItemName("Difficulty", ChatFormatting.WHITE, ChatFormatting.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(
                    plain("Current: ", ChatFormatting.GRAY).append(plain(getDifficultyName(pendingDifficulty), color)));
            lore.add(Component.empty());
            lore.add(plain("Easy → Normal → Hard", ChatFormatting.DARK_GRAY));
            lore.add(Component.empty());
            lore.add(plain("Click to cycle", ChatFormatting.DARK_GRAY));
            item.set(DataComponents.LORE, new ItemLore(lore));
            return item;
        }

        private ItemStack createSharedHealthItem() {
            List<Component> description = pendingSharedHealth
                    ? describe("One health and hunger bar for everyone.", "Damage to one is damage to all.")
                    : describe(
                            "Deaths only: everyone has their own",
                            "health and hunger, but when anyone",
                            "dies, everyone dies.");
            return toggleItem(
                    new ItemStack(pendingSharedHealth ? Items.REDSTONE : Items.SKELETON_SKULL),
                    "Shared Health",
                    ChatFormatting.RED,
                    pendingSharedHealth,
                    description);
        }

        private ItemStack createHalfHeartItem() {
            return toggleItem(
                    new ItemStack(pendingHalfHeart ? Items.GOLDEN_APPLE : Items.APPLE),
                    "Half-Heart Mode",
                    ChatFormatting.GOLD,
                    pendingHalfHeart,
                    describe("Everyone shares a single half heart.", "One hit and it is over."));
        }

        private ItemStack createSharedPotionsItem() {
            return toggleItem(
                    new ItemStack(pendingSharedPotions ? Items.DRAGON_BREATH : Items.GLASS_BOTTLE),
                    "Shared Effects",
                    ChatFormatting.LIGHT_PURPLE,
                    pendingSharedPotions,
                    describe("Potion effects apply to everyone.", "Good ones and bad ones alike."));
        }

        private ItemStack createSharedJumpingItem() {
            return toggleItem(
                    new ItemStack(pendingSharedJumping ? Items.RABBIT_FOOT : Items.FEATHER),
                    "Shared Jump",
                    ChatFormatting.GREEN,
                    pendingSharedJumping,
                    describe("When one player jumps,", "everyone jumps."));
        }

        private ItemStack createManhuntItem() {
            return toggleItem(
                    new ItemStack(pendingManhunt ? Items.COMPASS : Items.ENDER_EYE),
                    "Manhunt Mode",
                    ChatFormatting.DARK_PURPLE,
                    pendingManhunt,
                    describeWithServerModeNote(
                            "Runners share health; Hunters hunt.", "30s head start, hunter respawns."));
        }

        private ItemStack createSyncedInventoryItem() {
            return toggleItem(
                    new ItemStack(
                            pendingSyncedInventory
                                    ? Items.COPPER_CHEST.weathering().unaffected()
                                    : Items.CHEST),
                    "Synced Inventory",
                    ChatFormatting.DARK_AQUA,
                    pendingSyncedInventory,
                    describe("All players share the same inventory", "(main, hotbar, armor, offhand)."));
        }

        private ItemStack createWorldResetItem() {
            List<Component> description;
            if (pendingServerMode) {
                description = pendingWorldReset
                        ? describe("A death kills everyone and a fresh", "world is generated on the spot.")
                        : describe("A death kills everyone; all items drop", "in one pile and the world stays.");
            } else {
                description = pendingWorldReset
                        ? describe("A death ends the run for everyone.", "/start builds a brand new world.")
                        : describe(
                                "A death kills everyone; all items drop",
                                "in one pile and the run carries on.",
                                "/start keeps the current world.");
            }
            return toggleItem(
                    new ItemStack(pendingWorldReset ? Items.TNT : Items.BEDROCK),
                    "World Reset",
                    ChatFormatting.GOLD,
                    pendingWorldReset,
                    description);
        }

        private ItemStack createServerModeItem() {
            List<Component> description = pendingServerMode
                    ? describe(
                            "Soul Link is always on in its own world,",
                            "kept across restarts. No /start, no timer,",
                            "no victory screen. World Reset decides",
                            "what a death does. Turning off keeps the",
                            "world on disk for later.")
                    : describe(
                            "Speedrun mode: /start plays runs in fresh",
                            "worlds with a timer, ending at the dragon.",
                            "Turn on for an always-on Soul Link server.");
            List<Component> lore = new ArrayList<>(description);
            lore.add(Component.empty());
            lore.add(plain("Cannot be changed while a run is active.", ChatFormatting.DARK_GRAY));
            return toggleItem(
                    new ItemStack(pendingServerMode ? Items.BEACON : Items.CLOCK),
                    "Server Mode",
                    ChatFormatting.AQUA,
                    pendingServerMode,
                    lore);
        }

        private ItemStack createCombatLogItem() {
            return toggleItem(
                    new ItemStack(pendingDamageLog ? Items.WRITABLE_BOOK : Items.BOOK),
                    "Combat Log",
                    ChatFormatting.RED,
                    pendingDamageLog,
                    describe("Shows damage notifications in chat", "when players take damage."));
        }

        private ItemStack createTimerHudItem() {
            return toggleItem(
                    new ItemStack(pendingTimerHud ? Items.CLOCK : Items.BARRIER),
                    "Timer HUD",
                    ChatFormatting.YELLOW,
                    pendingTimerHud,
                    describeWithServerModeNote(
                            "Shows the run timer in the action bar.",
                            "The timer keeps running either way;",
                            "/runinfo still shows it."));
        }

        private ItemStack createHardcoreHeartsItem() {
            return toggleItem(
                    new ItemStack(pendingHardcoreHearts ? Items.WITHER_ROSE : Items.POPPY),
                    "Hardcore Hearts",
                    ChatFormatting.DARK_RED,
                    pendingHardcoreHearts,
                    describe(
                            "Draws the hardcore heart texture.",
                            "Cosmetic only; the world is not hardcore.",
                            "Applies when a player next joins."));
        }

        private ItemStack createJoinMessagesItem() {
            return toggleItem(
                    new ItemStack(pendingJoinMessages ? Items.PAPER : Items.MAP),
                    "Join Messages",
                    ChatFormatting.AQUA,
                    pendingJoinMessages,
                    describe("Sends the welcome text and command", "tips in chat when a player joins."));
        }

        private ItemStack createCommandsItem() {
            ItemStack item = new ItemStack(Items.COMMAND_BLOCK);
            item.set(DataComponents.CUSTOM_NAME, createItemName("Commands", ChatFormatting.GOLD, ChatFormatting.BOLD));
            List<Component> lore = new ArrayList<>();
            if (pendingServerMode) {
                lore.add(plain("/settings", ChatFormatting.GREEN).append(plain(" - This menu", ChatFormatting.GRAY)));
                lore.add(plain("/runinfo", ChatFormatting.GREEN)
                        .append(plain(" - Shared health and hunger", ChatFormatting.GRAY)));
                lore.add(plain("/stoprun", ChatFormatting.RED)
                        .append(plain(" - Generate a fresh world (admin)", ChatFormatting.GRAY)));
            } else {
                lore.add(
                        plain("/start", ChatFormatting.GREEN).append(plain(" - Start a new run", ChatFormatting.GRAY)));
                lore.add(plain("/settings", ChatFormatting.GREEN).append(plain(" - This menu", ChatFormatting.GRAY)));
                lore.add(plain("/runinfo", ChatFormatting.GREEN)
                        .append(plain(" - Run state and stats", ChatFormatting.GRAY)));
                lore.add(plain("/reset", ChatFormatting.GREEN)
                        .append(plain(" - End the current run", ChatFormatting.GRAY)));
                lore.add(plain("/stoprun", ChatFormatting.RED)
                        .append(plain(" - End the run (admin)", ChatFormatting.GRAY)));
            }
            lore.add(Component.empty());
            lore.add(plain("Changes save when you close this menu.", ChatFormatting.DARK_GRAY));
            item.set(DataComponents.LORE, new ItemLore(lore));
            return item;
        }

        public Settings.SettingsSnapshot getOriginal() {
            return original;
        }

        public Settings.SettingsSnapshot getPendingSnapshot() {
            return new Settings.SettingsSnapshot(
                    pendingDifficulty,
                    pendingHalfHeart,
                    pendingSharedPotions,
                    pendingSharedJumping,
                    pendingManhunt,
                    pendingSyncedInventory,
                    pendingWorldReset,
                    pendingSharedHealth);
        }

        public boolean hasChanges() {
            return !getPendingSnapshot().equals(original)
                    || pendingServerMode != originalServerMode
                    || pendingDamageLog != originalDamageLog
                    || pendingTimerHud != originalTimerHud
                    || pendingHardcoreHearts != originalHardcoreHearts
                    || pendingJoinMessages != originalJoinMessages;
        }

        public void cycleDifficulty() {
            pendingDifficulty = switch (pendingDifficulty) {
                case PEACEFUL, EASY -> Difficulty.NORMAL;
                case NORMAL -> Difficulty.HARD;
                case HARD -> Difficulty.EASY;
            };
        }

        public void toggleSharedHealth() {
            pendingSharedHealth = !pendingSharedHealth;
        }

        public void toggleHalfHeart() {
            pendingHalfHeart = !pendingHalfHeart;
        }

        public void toggleSharedPotions() {
            pendingSharedPotions = !pendingSharedPotions;
        }

        public void toggleSharedJumping() {
            pendingSharedJumping = !pendingSharedJumping;
        }

        public void toggleManhunt() {
            pendingManhunt = !pendingManhunt;
        }

        public void toggleSyncedInventory() {
            pendingSyncedInventory = !pendingSyncedInventory;
        }

        public void toggleWorldReset() {
            pendingWorldReset = !pendingWorldReset;
        }

        public void toggleServerMode() {
            pendingServerMode = !pendingServerMode;
        }

        public void toggleDamageLog() {
            pendingDamageLog = !pendingDamageLog;
        }

        public void toggleTimerHud() {
            pendingTimerHud = !pendingTimerHud;
        }

        public void toggleHardcoreHearts() {
            pendingHardcoreHearts = !pendingHardcoreHearts;
        }

        public void toggleJoinMessages() {
            pendingJoinMessages = !pendingJoinMessages;
        }
    }

    /**
     * Slot that cannot be picked from or placed into.
     */
    private static class VirtualSlot extends Slot {
        VirtualSlot(Container inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean mayPickup(Player playerEntity) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean isHighlightable() {
            return true;
        }
    }

    /**
     * The menu itself. Clicks flip values; closing applies them.
     */
    public static class SettingsScreenHandler extends ChestMenu {

        private final SettingsInventory settingsInventory;
        private final ServerPlayer player;
        private boolean applied = false;

        public SettingsScreenHandler(int syncId, SettingsInventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, syncId, player.getInventory(), inventory, 6);
            this.settingsInventory = inventory;
            this.player = player;

            for (int i = 0; i < INVENTORY_SIZE; i++) {
                Slot oldSlot = this.slots.get(i);
                this.slots.set(i, new VirtualSlot(inventory, i, oldSlot.x, oldSlot.y));
            }
        }

        @Override
        public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
            if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                handleSettingsClick(slotIndex);
                setCarried(ItemStack.EMPTY);

                // Spectators ignore the normal sync packets; force a full resync for them.
                if (player instanceof ServerPlayer serverPlayer
                        && serverPlayer.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                    ((ScreenHandlerAccessor) this).invokeUpdateToClient();
                } else {
                    super.broadcastChanges();
                }
                return;
            }
            super.clicked(slotIndex, buttonNum, containerInput, player);
        }

        @Override
        public ItemStack quickMoveStack(Player playerEntity, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            if (slot.container == settingsInventory) {
                return false;
            }
            return super.canTakeItemForPickAll(stack, slot);
        }

        private boolean runActive() {
            RunState state = RunManager.getInstance().getGameState();
            return state == RunState.RUNNING || state == RunState.GENERATING_WORLD;
        }

        private void handleSettingsClick(int slotIndex) {
            switch (slotIndex) {
                case DIFFICULTY_SLOT -> settingsInventory.cycleDifficulty();
                case SHARED_HEALTH_SLOT -> settingsInventory.toggleSharedHealth();
                case HALF_HEART_SLOT -> settingsInventory.toggleHalfHeart();
                case SHARED_POTIONS_SLOT -> settingsInventory.toggleSharedPotions();
                case SHARED_JUMPING_SLOT -> settingsInventory.toggleSharedJumping();
                case MANHUNT_SLOT -> settingsInventory.toggleManhunt();
                case SYNCED_INVENTORY_SLOT -> settingsInventory.toggleSyncedInventory();
                case WORLD_RESET_SLOT -> settingsInventory.toggleWorldReset();
                case SERVER_MODE_SLOT -> {
                    if (runActive()) {
                        player.sendSystemMessage(
                                RunManager.formatMessage(
                                        "Server Mode cannot change while a run is active. End it with /stoprun or /reset first."));
                        return;
                    }
                    settingsInventory.toggleServerMode();
                }
                case COMBAT_LOG_SLOT -> settingsInventory.toggleDamageLog();
                case TIMER_HUD_SLOT -> settingsInventory.toggleTimerHud();
                case HARDCORE_HEARTS_SLOT -> settingsInventory.toggleHardcoreHearts();
                case JOIN_MESSAGES_SLOT -> settingsInventory.toggleJoinMessages();
                case COMMANDS_SLOT -> {
                    // Informational; the lore says it all.
                    return;
                }
                default -> {
                    return;
                }
            }
            settingsInventory.populateItems();
            playClickSound();
        }

        @Override
        public void removed(Player closingPlayer) {
            super.removed(closingPlayer);
            if (applied) return;
            applied = true;
            applyChanges();
        }

        /**
         * Applies everything that differs from what the menu opened with, saves, and tells chat.
         */
        private void applyChanges() {
            if (!settingsInventory.hasChanges()) return;

            RunManager runManager = RunManager.getInstance();
            MinecraftServer server = runManager.getServer();
            if (server == null) return;

            Settings settings = Settings.getInstance();
            SettingsInventory inv = settingsInventory;
            List<Component> changes = new ArrayList<>();
            boolean deferred = false;

            // Server Mode first: it decides how the rest applies.
            if (inv.pendingServerMode != inv.originalServerMode) {
                if (inv.pendingServerMode) {
                    if (runManager.enterServerMode()) {
                        changes.add(changeLine("Server Mode", "OFF", "ON"));
                    } else {
                        player.sendSystemMessage(RunManager.formatMessage("Server Mode not changed: a run is active."));
                    }
                } else {
                    runManager.leaveServerMode();
                    changes.add(changeLine("Server Mode", "ON", "OFF"));
                }
            }

            Settings.SettingsSnapshot before = inv.getOriginal();
            Settings.SettingsSnapshot after = inv.getPendingSnapshot();
            if (!after.equals(before)) {
                deferred = runActive() && !settings.isServerMode();
                settings.applySnapshot(after);
                if (!deferred && settings.isServerMode()) {
                    runManager.applyServerModeSettings(player, before, after);
                }
                if (after.difficulty() != before.difficulty()) {
                    changes.add(changeLine(
                            "Difficulty",
                            getDifficultyName(before.difficulty()),
                            getDifficultyName(after.difficulty())));
                    if (settings.isServerMode()) {
                        changes.add(Component.empty()
                                .append(RunManager.getPrefix())
                                .append(plain(
                                        "    (takes effect when the world is next generated)",
                                        ChatFormatting.DARK_GRAY)));
                    }
                }
                if (after.sharedHealth() != before.sharedHealth()) {
                    changes.add(changeLine("Shared Health", onOff(before.sharedHealth()), onOff(after.sharedHealth())));
                }
                if (after.halfHeartMode() != before.halfHeartMode()) {
                    changes.add(
                            changeLine("Half Heart Mode", onOff(before.halfHeartMode()), onOff(after.halfHeartMode())));
                }
                if (after.sharedPotions() != before.sharedPotions()) {
                    changes.add(
                            changeLine("Shared Effects", onOff(before.sharedPotions()), onOff(after.sharedPotions())));
                }
                if (after.sharedJumping() != before.sharedJumping()) {
                    changes.add(changeLine("Shared Jump", onOff(before.sharedJumping()), onOff(after.sharedJumping())));
                }
                if (after.manhuntMode() != before.manhuntMode()) {
                    changes.add(changeLine("Manhunt Mode", onOff(before.manhuntMode()), onOff(after.manhuntMode())));
                }
                if (after.syncedInventory() != before.syncedInventory()) {
                    changes.add(changeLine(
                            "Synced Inventory", onOff(before.syncedInventory()), onOff(after.syncedInventory())));
                }
                if (after.worldReset() != before.worldReset()) {
                    changes.add(changeLine("World Reset", onOff(before.worldReset()), onOff(after.worldReset())));
                }
            }

            if (inv.pendingDamageLog != inv.originalDamageLog) {
                settings.setDamageLogEnabled(inv.pendingDamageLog);
                changes.add(changeLine("Combat Log", onOff(inv.originalDamageLog), onOff(inv.pendingDamageLog)));
            }
            if (inv.pendingTimerHud != inv.originalTimerHud) {
                settings.setTimerHudEnabled(inv.pendingTimerHud);
                changes.add(changeLine("Timer HUD", onOff(inv.originalTimerHud), onOff(inv.pendingTimerHud)));
            }
            if (inv.pendingHardcoreHearts != inv.originalHardcoreHearts) {
                settings.setHardcoreHearts(inv.pendingHardcoreHearts);
                changes.add(changeLine(
                        "Hardcore Hearts", onOff(inv.originalHardcoreHearts), onOff(inv.pendingHardcoreHearts)));
            }
            if (inv.pendingJoinMessages != inv.originalJoinMessages) {
                settings.setJoinMessagesEnabled(inv.pendingJoinMessages);
                changes.add(
                        changeLine("Join Messages", onOff(inv.originalJoinMessages), onOff(inv.pendingJoinMessages)));
            }

            if (changes.isEmpty()) return;

            SettingsPersistence.save(server);
            playConfirmSound();

            server.getPlayerList()
                    .broadcastSystemMessage(
                            Component.empty()
                                    .append(RunManager.getPrefix())
                                    .append(Component.literal(player.getName().getString())
                                            .withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(" changed settings:")
                                            .withStyle(ChatFormatting.GRAY)),
                            false);
            for (Component line : changes) {
                server.getPlayerList().broadcastSystemMessage(line, false);
            }
            if (deferred) {
                server.getPlayerList()
                        .broadcastSystemMessage(
                                Component.empty()
                                        .append(RunManager.getPrefix())
                                        .append(Component.literal("Run settings apply on the next run.")
                                                .withStyle(ChatFormatting.YELLOW)),
                                false);
            }
        }

        private static Component changeLine(String name, String oldValue, String newValue) {
            return Component.empty()
                    .append(RunManager.getPrefix())
                    .append(plain("  • " + name + ": ", ChatFormatting.GRAY))
                    .append(plain(oldValue, ChatFormatting.RED))
                    .append(plain(" → ", ChatFormatting.DARK_GRAY))
                    .append(plain(newValue, ChatFormatting.GREEN));
        }

        private void playClickSound() {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,
                    net.minecraft.sounds.SoundSource.MASTER,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    0.5f,
                    1.0f,
                    player.getRandom().nextLong()));
        }

        private void playConfirmSound() {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(
                            net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP),
                    net.minecraft.sounds.SoundSource.MASTER,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    0.5f,
                    1.0f,
                    player.getRandom().nextLong()));
        }

        @Override
        public boolean stillValid(Player playerEntity) {
            return true;
        }
    }
}
