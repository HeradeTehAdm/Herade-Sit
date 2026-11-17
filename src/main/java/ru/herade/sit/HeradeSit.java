package ru.herade.sit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HeradeSit implements ModInitializer {
    public static final String MOD_ID = "herade_sit";

    private static final Set<UUID> SIT_MODE_ENABLED = new HashSet<>();
    private static final Map<UUID, ArmorStand> ACTIVE_SEATS = new HashMap<>();

    @Override
    public void onInitialize() {
        registerCommands();
        registerBlockInteraction();
        registerCleanup();
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("sit").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                UUID playerId = player.getUUID();

                if (SIT_MODE_ENABLED.remove(playerId)) {
                    stopSitting(player);
                    player.sendSystemMessage(Component.literal("§eРежим сидения выключен."));
                } else {
                    SIT_MODE_ENABLED.add(playerId);
                    player.sendSystemMessage(Component.literal(
                        "§aРежим сидения включён. Нажмите ПКМ по ступеньке или полублоку, чтобы сесть."
                    ));
                }

                return 1;
            }))
        );
    }

    private static void registerBlockInteraction() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide()) {
                return InteractionResult.PASS;
            }

            if (!(player instanceof ServerPlayer serverPlayer)
                || hand != InteractionHand.MAIN_HAND
                || !SIT_MODE_ENABLED.contains(serverPlayer.getUUID())
                || serverPlayer.isSpectator()
                || serverPlayer.isPassenger()) {
                return InteractionResult.PASS;
            }

            BlockPos blockPos = hitResult.getBlockPos();
            BlockState state = level.getBlockState(blockPos);
            if (!(state.getBlock() instanceof StairBlock)
                && !(state.getBlock() instanceof SlabBlock)) {
                return InteractionResult.PASS;
            }

            if (!(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }

            Vec3 seatPosition = findSeatPosition(level, blockPos, state, hitResult.getLocation());
            if (seatPosition == null) {
                return InteractionResult.PASS;
            }

            return createSeat(serverPlayer, serverLevel, seatPosition)
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS;
        });
    }

    private static Vec3 findSeatPosition(
        net.minecraft.world.level.Level level,
        BlockPos blockPos,
        BlockState state,
        Vec3 hitLocation
    ) {
        VoxelShape shape = state.getCollisionShape(level, blockPos);
        if (shape.isEmpty()) {
            return null;
        }

        double localX = hitLocation.x - blockPos.getX();
        double localZ = hitLocation.z - blockPos.getZ();

        AABB selectedBox = null;
        double nearestDistance = Double.MAX_VALUE;
        boolean foundContainingBox = false;

        for (AABB box : shape.toAabbs()) {
            boolean containsHit = localX >= box.minX - 1.0E-4
                && localX <= box.maxX + 1.0E-4
                && localZ >= box.minZ - 1.0E-4
                && localZ <= box.maxZ + 1.0E-4;

            double clampedX = clamp(localX, box.minX, box.maxX);
            double clampedZ = clamp(localZ, box.minZ, box.maxZ);
            double dx = localX - clampedX;
            double dz = localZ - clampedZ;
            double distance = dx * dx + dz * dz;

            if (containsHit) {
                if (!foundContainingBox || selectedBox == null || box.maxY > selectedBox.maxY) {
                    selectedBox = box;
                    foundContainingBox = true;
                }
            } else if (!foundContainingBox && distance < nearestDistance) {
                selectedBox = box;
                nearestDistance = distance;
            }
        }

        if (selectedBox == null) {
            return null;
        }

        double x = clamp(localX, selectedBox.minX + 0.12, selectedBox.maxX - 0.12);
        double z = clamp(localZ, selectedBox.minZ + 0.12, selectedBox.maxZ - 0.12);
        double y = selectedBox.maxY;

        return new Vec3(blockPos.getX() + x, blockPos.getY() + y, blockPos.getZ() + z);
    }

    private static double clamp(double value, double min, double max) {
        if (min > max) {
            return (min + max) * 0.5;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean createSeat(ServerPlayer player, ServerLevel level, Vec3 surfacePosition) {
        ArmorStand seat = new ArmorStand(level, surfacePosition.x, surfacePosition.y, surfacePosition.z);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        seat.setSilent(true);
        seat.setNoBasePlate(true);
        seat.setShowArms(false);
        seat.setYRot(player.getYRot());

        Vec3 passengerOffset = seat.getPassengerRidingPosition(player).subtract(seat.position());
        seat.setPos(
            surfacePosition.x - passengerOffset.x,
            surfacePosition.y - passengerOffset.y,
            surfacePosition.z - passengerOffset.z
        );

        if (!level.addFreshEntity(seat)) {
            return false;
        }

        if (!player.startRiding(seat, true, true)) {
            seat.remove(RemovalReason.DISCARDED);
            return false;
        }

        ACTIVE_SEATS.put(player.getUUID(), seat);
        return true;
    }

    private static void registerCleanup() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SIT_MODE_ENABLED.remove(handler.player.getUUID());
            stopSitting(handler.player);
        });

        ServerTickEvents.END_SERVER_TICK.register(HeradeSit::cleanupInvalidSeats);
    }

    private static void cleanupInvalidSeats(MinecraftServer server) {
        Iterator<Map.Entry<UUID, ArmorStand>> iterator = ACTIVE_SEATS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, ArmorStand> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            ArmorStand seat = entry.getValue();

            if (player == null || !player.isAlive() || player.getVehicle() != seat || seat.isRemoved()) {
                seat.ejectPassengers();
                seat.remove(RemovalReason.DISCARDED);
                iterator.remove();
            }
        }
    }

    private static void stopSitting(ServerPlayer player) {
        ArmorStand seat = ACTIVE_SEATS.remove(player.getUUID());
        if (seat == null) {
            return;
        }

        if (player.getVehicle() == seat) {
            player.stopRiding();
        }

        seat.ejectPassengers();
        seat.remove(RemovalReason.DISCARDED);
    }
}
