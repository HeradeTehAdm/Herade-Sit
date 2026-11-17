package ru.herade.sit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HeradeSit implements ModInitializer {
    public static final String MOD_ID = "herade_sit";
    private static final Set<UUID> SIT_MODE_ENABLED = new HashSet<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("sit").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                UUID playerId = player.getUUID();

                if (SIT_MODE_ENABLED.remove(playerId)) {
                    player.sendSystemMessage(Component.literal("§eРежим сидения выключен."));
                } else {
                    SIT_MODE_ENABLED.add(playerId);
                    player.sendSystemMessage(Component.literal("§aРежим сидения включён. Нажмите ПКМ по ступеньке или полублоку, чтобы сесть."));
                }

                return 1;
            }))
        );

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            SIT_MODE_ENABLED.remove(handler.player.getUUID())
        );
    }

    public static boolean isSitModeEnabled(ServerPlayer player) {
        return SIT_MODE_ENABLED.contains(player.getUUID());
    }
}
