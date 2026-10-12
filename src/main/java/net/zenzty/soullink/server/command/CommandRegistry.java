package net.zenzty.soullink.server.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.SpeedrunnerSelectorGui;
import net.zenzty.soullink.server.run.ClientDimensionNames;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsGui;
import net.zenzty.soullink.server.settings.SettingsPersistence;

/**
 * Registers all mod commands: /start, /stoprun, /runinfo, /settings, /reset, /clockfix
 */
public class CommandRegistry {

    /**
     * Registers all commands for the SoulLink mod.
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /start - Start a new run
            dispatcher.register(Commands.literal("start").executes(CommandRegistry::handleStart));

            // /stoprun - Admin command to stop current run (requires
            // gamemaster permission)
            dispatcher.register(Commands.literal("stoprun")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(CommandRegistry::handleStopRun));

            // /runinfo - Display current run info
            dispatcher.register(Commands.literal("runinfo").executes(CommandRegistry::handleRunInfo));

            // /settings - The one settings menu (saves on close)
            dispatcher.register(Commands.literal("settings").executes(CommandRegistry::handleSettings));

            // /reset - Manually reset the current run
            dispatcher.register(Commands.literal("reset").executes(CommandRegistry::handleReset));

            // /clockfix [on|off] - Admin: tell clients vanilla dimension names, so clocks work
            dispatcher.register(Commands.literal("clockfix")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> handleClockFix(context, null))
                    .then(Commands.literal("on").executes(context -> handleClockFix(context, true)))
                    .then(Commands.literal("off").executes(context -> handleClockFix(context, false))));
        });
    }

    /**
     * Shows or sets the clock fix (see ClientDimensionNames). A change reaches each player from their
     * next join or dimension change, and is saved with the settings.
     */
    private static int handleClockFix(CommandContext<CommandSourceStack> context, Boolean on) {
        if (on != null) {
            ClientDimensionNames.setEnabled(on);
            SettingsPersistence.save(context.getSource().getServer());
        }
        String state = ClientDimensionNames.isEnabled() ? "on" : "off";
        String message = on == null
                ? "Clock fix is " + state + "."
                : "Clock fix " + state + ". Players get it the next time they join or change dimension.";
        context.getSource().sendSuccess(() -> RunManager.formatMessage(message), on != null);
        return Command.SINGLE_SUCCESS;
    }

    private static int handleStart(CommandContext<CommandSourceStack> context) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            context.getSource().sendFailure(RunManager.formatMessage("Run manager not initialized."));
            return 0;
        }

        if (runManager == null) {
            context.getSource().sendFailure(RunManager.formatMessage("Run manager not initialized."));
            return 0;
        }

        if (Settings.getInstance().isServerMode()) {
            context.getSource().sendFailure(serverModeNotice());
            return 0;
        }

        // Manhunt disabled (including pending): start immediately without opening the
        // selector
        if (!Settings.getInstance().isManhuntModeForNextRun()) {
            runManager.startRun();
            return Command.SINGLE_SUCCESS;
        }

        // Manhunt enabled: open the Runner/Hunter selector; startRun is called from the GUI
        // on confirm
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            SpeedrunnerSelectorGui.open(player);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendFailure(RunManager.formatMessage("Only players can start a Manhunt run."));
        return 0;
    }

    private static int handleStopRun(CommandContext<CommandSourceStack> context) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            context.getSource().sendFailure(RunManager.formatMessage("Run manager not initialized."));
            return 0;
        }

        if (Settings.getInstance().isServerMode()) {
            // No run to stop; the admin equivalent is a fresh server world.
            if (runManager == null || !runManager.isRunActive()) {
                context.getSource().sendFailure(RunManager.formatMessage("The server world is not ready yet."));
                return 0;
            }
            runManager.resetServerWorld(null, null);
            return Command.SINGLE_SUCCESS;
        }

        if (runManager == null || !runManager.isRunActive()) {
            context.getSource().sendFailure(RunManager.formatMessage("No active run."));
            return 0;
        }

        runManager.triggerGameOver();

        context.getSource().sendSuccess(() -> RunManager.formatMessage("Run stopped."), true);

        return Command.SINGLE_SUCCESS;
    }

    private static int handleRunInfo(CommandContext<CommandSourceStack> context) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            context.getSource().sendFailure(RunManager.formatMessage("Run manager not initialized."));
            return 0;
        }

        if (runManager == null) {
            context.getSource().sendFailure(RunManager.formatMessage("Run manager not initialized."));
            return 0;
        }

        boolean serverMode = Settings.getInstance().isServerMode();
        Component info = Component.empty()
                .append(RunManager.getPrefix())
                .append(Component.literal("State: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(
                                serverMode
                                        ? "SERVER MODE"
                                        : runManager.getGameState().name())
                        .withStyle(ChatFormatting.WHITE))
                .append(
                        serverMode
                                ? Component.empty()
                                : Component.empty()
                                        .append(Component.literal(" | Time: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(runManager.getFormattedTime())
                                                .withStyle(ChatFormatting.WHITE)))
                .append(
                        Settings.getInstance().isSharedHealth()
                                ? Component.empty()
                                : Component.literal(" | Health: not shared").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Settings.getInstance().isSharedHealth() ? " | Health: " : " | Pool: ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format("%.1f", SharedStatsHandler.getSharedHealth()))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Hunger: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(SharedStatsHandler.getSharedHunger()))
                        .withStyle(ChatFormatting.WHITE));

        context.getSource().sendSuccess(() -> info, false);

        return Command.SINGLE_SUCCESS;
    }

    private static int handleSettings(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            SettingsGui.open(player);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendFailure(RunManager.formatMessage("Only players can use this command."));
        return 0;
    }

    private static Component serverModeNotice() {
        return RunManager.formatMessage(
                "Server Mode is on: Soul Link is always active and there are no runs. Turn it off in /settings.");
    }

    private static int handleReset(CommandContext<CommandSourceStack> context) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            context.getSource().sendFailure(RunManager.formatMessage("Run manager not initialized."));
            return 0;
        }

        if (Settings.getInstance().isServerMode()) {
            context.getSource().sendFailure(serverModeNotice());
            return 0;
        }

        if (runManager == null || !runManager.isRunActive()) {
            context.getSource().sendFailure(RunManager.formatMessage("No active run to reset."));
            return 0;
        }

        // Perform the reset first
        runManager.triggerGameOver();

        // Broadcast reset message to all players after successful reset
        runManager
                .getServer()
                .getPlayerList()
                .broadcastSystemMessage(RunManager.formatMessage("Run has been reset."), false);
        return Command.SINGLE_SUCCESS;
    }
}
