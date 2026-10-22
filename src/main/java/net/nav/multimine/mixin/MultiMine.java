package net.nav.multimine.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.entity.player.BlockBreakingInfo;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.render.WorldRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.IntFunction;
import java.util.function.ObjIntConsumer;
import java.util.function.IntUnaryOperator;
import java.nio.file.Files;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.JsonParser;

@Mixin(ServerPlayerInteractionManager.class)
public abstract class MultiMine {
    @Shadow protected ServerWorld world;
    @Shadow protected ServerPlayerEntity player;
    @Shadow private boolean mining;

    @SuppressWarnings("unchecked") private Long2ObjectOpenHashMap<MiningEntry> multimine$map() {
        return (Long2ObjectOpenHashMap<MiningEntry>) ((IntFunction<?>) world).apply(0);
    }
    private void multimine$changed(MiningEntry oldEntry) {
        if (oldEntry != null) world.setBlockBreakingInfo(oldEntry.getActorId(),oldEntry.getPos(),-1);
    }
    private void multimine$update(MiningEntry entry,float progress) {
        int crackStage = progress < 1F ? (int) (progress * 10F) : 9;
        float oldProgress = entry.progress;
        entry.progress = Math.min(progress,1F);
        if (MiningEntry.RegenAmount > 0F) ((IntFunction<?>) world).apply(MiningEntry.RegenDelay);
        if (entry.getStage() != crackStage) {
            entry.setStage(crackStage);
            world.setBlockBreakingInfo(entry.getActorId(),entry.getPos(),crackStage);
        } else if (!(progress >= 1F && oldProgress < 1F)) return;
        player.networkHandler.sendPacket(new BlockBreakingProgressS2CPacket(
                Float.floatToRawIntBits(-entry.progress),entry.getPos(),Byte.MAX_VALUE));
    }
    @Redirect(method = "update",at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;continueMining(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;I)F"))
    private float multimine$continueMining(ServerPlayerInteractionManager manager,BlockState state,BlockPos pos,int age) {
        var entries = multimine$map();
        long posKey = pos.asLong();
        MiningEntry entry = entries.get(posKey);
        if (entry == null || entry.trackedBlock != state.getBlock()) {
            float breakingDelta = state.calcBlockBreakingDelta(player,world,pos);
            if (!(breakingDelta > 0F)) {if (entry != null) multimine$changed(entries.remove(posKey)); return 0F;}
            MiningEntry newEntry = new MiningEntry(pos,(int) world.getTime(),player.server.getTicks(),state.getBlock(),breakingDelta);
            newEntry.maxDelta = breakingDelta;
            multimine$changed(entries.put(posKey,newEntry));
            multimine$update(newEntry,breakingDelta);
            return newEntry.progress;
        }
        int worldTick = (int) world.getTime();
        float breakingDelta = state.calcBlockBreakingDelta(player,world,pos);
        if (!(breakingDelta > 0F)) return entry.getProgressAt(worldTick);
        int serverTick = player.server.getTicks();
        float progress = entry.progress;
        if (entry.lastTick != serverTick) {
            if (worldTick - entry.getLastUpdateTick() > 1) {progress = Math.max(0F,entry.getProgressAt(worldTick));entry.setStage(-1);}
            entry.setLastUpdateTick(worldTick);
            entry.lastTick = serverTick;
            entry.maxDelta = breakingDelta;
            progress += breakingDelta;
        } else {
            float maxDelta = entry.maxDelta;
            if (maxDelta == 0F) return progress;
            if (breakingDelta > maxDelta) {
                entry.maxDelta = breakingDelta;
                progress += breakingDelta - maxDelta * (1F - MiningEntry.PlayerMultiplier);
            } else {progress += breakingDelta * MiningEntry.PlayerMultiplier;}
        }
        multimine$update(entry,progress);
        return progress;
    }
    @ModifyExpressionValue(method = "processBlockBreakingAction",at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;calcBlockBreakingDelta(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)F"))
    private float multimine$action(float delta,BlockPos pos,Action action,@Local(ordinal = 0) BlockState state) {
        if (action == Action.START_DESTROY_BLOCK && delta >= 1F) return delta;
        Long2ObjectOpenHashMap<MiningEntry> entries = multimine$map();
        long posKey = pos.asLong();
        MiningEntry entry = entries.get(posKey);
        int worldTick = (int) world.getTime();
        float stored = entry != null && entry.trackedBlock == state.getBlock() ? entry.getProgressAt(worldTick) : 0F;
        if (!(stored > 0F)) {
            if (action == Action.START_DESTROY_BLOCK && delta > 0F) {
                MiningEntry newEntry = new MiningEntry(pos,worldTick,player.server.getTicks(),state.getBlock(),Float.MIN_VALUE);
                multimine$changed(entries.put(posKey,newEntry));
                multimine$update(newEntry,Float.MIN_VALUE);
            } else if (entry != null) {multimine$changed(entries.remove(posKey));}
            return action == Action.START_DESTROY_BLOCK ? delta : 0F;
        }
        if (action != Action.START_DESTROY_BLOCK) return stored >= 0.7F ? 0.7F : 0F;
        int serverTick = player.server.getTicks();
        if (entry.lastTick != serverTick) {entry.maxDelta = 0F;entry.setLastUpdateTick(worldTick);entry.lastTick = serverTick;}
        entry.setStage(-1);
        multimine$update(entry,stored);
        return delta;
    }
    @ModifyExpressionValue(method = "tryBreakBlock",at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"))
    private boolean multimine$clear(boolean removed,BlockPos pos) {
        if (removed) multimine$changed(multimine$map().remove(pos.asLong())); return removed;
    }
    @WrapWithCondition(method = {"processBlockBreakingAction","update"},at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockBreakingInfo(ILnet/minecraft/util/math/BlockPos;I)V"))
    private boolean multimine$hideCrack(ServerWorld world,int id,BlockPos pos,int crackStage) {
        if (crackStage < 0 && mining) multimine$changed(multimine$map().remove(pos.asLong())); return false;
    }
    private static final class MiningEntry extends BlockBreakingInfo implements ObjIntConsumer<Object>,IntUnaryOperator {
        int lastTick;
        float progress,maxDelta;
        final Block trackedBlock;
        private static int nextId;
        private static final int RegenDelay,RegenCooldown;
        private static final float RegenAmount,PlayerMultiplier;
        static {
            int regenDelay = 100,regenCooldown = 50;
            float regenAmount = 0.1F,playerMultiplier = 0.5F;
            var path = FabricLoader.getInstance().getConfigDir().resolve("multi-mine.txt");
            try {
                try {
                    var config = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    try {regenDelay = config.get("RegenDelay").getAsInt();} catch (Exception ignored) {}
                    try {regenAmount = config.get("RegenAmount").getAsFloat();} catch (Exception ignored) {}
                    try {regenCooldown = config.get("RegenCooldown").getAsInt();} catch (Exception ignored) {}
                    try {playerMultiplier = config.get("PlayerMultiplier").getAsFloat();} catch (Exception ignored) {}
                } catch (java.nio.file.NoSuchFileException ignored) {
                    Files.writeString(path,"{\n \"RegenDelay\":100,\n \"RegenAmount\":0.1,\n \"RegenCooldown\":50,\n \"PlayerMultiplier\":0.5\n}");
                }
            } catch (Exception ignored) {}
            RegenDelay = Math.max(regenDelay,1);
            RegenAmount = regenAmount >= 0F && regenAmount <= Float.MAX_VALUE ? regenAmount : 0F;
            RegenCooldown = Math.max(regenCooldown,1);
            PlayerMultiplier = playerMultiplier >= 0F && playerMultiplier <= Float.MAX_VALUE ? playerMultiplier : 0F;
        }
        MiningEntry(BlockPos pos,int worldTick,int serverTick,Block trackedBlock,float p) {
            super(--nextId,pos);
            setStage(-1);
            setLastUpdateTick(worldTick);
            lastTick = serverTick;
            this.trackedBlock = trackedBlock;
            progress = p;
        }
        float getProgressAt(int worldTick) {
            int t = worldTick - getLastUpdateTick() - RegenDelay;
            if (t < 0) return progress;
            return progress - (t / RegenCooldown + 1) * RegenAmount;
        }
        @Override public void accept(Object currentBlock,int worldTick) {
            float regenerated = getProgressAt(worldTick);
            if (!(regenerated > 0F) || currentBlock != null && currentBlock != trackedBlock) {
                setStage(-1);
            } else if (currentBlock != null && regenerated != progress) {setStage((int) (regenerated * 10F));}
        }
        @Override public int applyAsInt(int worldTick) {
            int t = worldTick - getLastUpdateTick() - RegenDelay;
            return t < 0 ? -t : RegenCooldown - t % RegenCooldown;
        }
    }
}
@Mixin(ServerWorld.class) abstract class MultiMineWorld implements IntFunction<Object> {
    private final Long2ObjectOpenHashMap<BlockBreakingInfo> multimine$entries = new Long2ObjectOpenHashMap<>(1);
    private int multimine$scan;
    @Override public Object apply(int i) {
        if (i != 0) multimine$scan = multimine$entries.isEmpty() ? 0 : multimine$entries.size() == 1 ? i :
                multimine$scan == 0 ? i : Math.min(multimine$scan,i);
        return multimine$entries;
    }
    @WrapWithCondition(method = "tick",at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickTime()V"))
    @SuppressWarnings("unchecked") private boolean multimine$regenerate(ServerWorld world) {
        if (multimine$scan == 0 || --multimine$scan > 0) return true;
        int worldTick = (int) world.getTime(),nextScan = Integer.MAX_VALUE;
        var chunks = world.getChunkManager();
        boolean noPlayers = world.getPlayers().isEmpty();
        var entries = multimine$entries;
        var iterator = entries.values().iterator();

        while (iterator.hasNext()) {
            BlockBreakingInfo entry = iterator.next();
            BlockPos pos = entry.getPos();
            var chunk = chunks.getWorldChunk(pos.getX() >> 4,pos.getZ() >> 4);
            int oldStage = entry.getStage();
            ((ObjIntConsumer<Object>) entry).accept(chunk != null ? chunk.getBlockState(pos).getBlock() : null,worldTick);
            int currentStage = entry.getStage();
            if (currentStage >= 0) {
                nextScan = Math.min(nextScan,((IntUnaryOperator) entry).applyAsInt(worldTick));
                if (noPlayers || oldStage == currentStage) continue;
            }
            if (!noPlayers && chunk != null) world.setBlockBreakingInfo(entry.getActorId(),pos,currentStage);
            if (currentStage < 0) iterator.remove();
        }
        if (entries.isEmpty()) entries.trim();
        else multimine$scan = nextScan;
        return true;
    }
}
@Mixin(ClientPlayerInteractionManager.class)
abstract class MultiMineClient implements ObjIntConsumer<Object> {
    @Shadow private MinecraftClient client;
    @Shadow private BlockPos currentBreakingPos;
    @Shadow private float currentBreakingProgress;
    @Shadow private boolean breakingBlock;

    @Override public void accept(Object blockPos,int b) {
        if (breakingBlock && currentBreakingPos.equals(blockPos)) {
            currentBreakingProgress = -Float.intBitsToFloat(b);
            if (client.player != null) client.worldRenderer.setBlockBreakingInfo(client.player.getId(),(BlockPos) blockPos,-1);
        }
    }
    @Redirect(method = {"attackBlock","updateBlockBreakingProgress"},at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;isCurrentlyBreaking(Lnet/minecraft/util/math/BlockPos;)Z"))
    private boolean multimine$sameBlock(ClientPlayerInteractionManager manager,BlockPos pos) {
        return breakingBlock && pos.equals(currentBreakingPos);
    }
}
@Mixin(ClientWorld.class) abstract class MultiMineClientWorld {
    @Shadow private MinecraftClient client;
    private BlockPos multimine$pos;

    @WrapWithCondition(method = "setBlockBreakingInfo",at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;setBlockBreakingInfo(ILnet/minecraft/util/math/BlockPos;I)V"))
    @SuppressWarnings("unchecked") private boolean multimine$handler(WorldRenderer renderer,int id,BlockPos pos,int crackStage) {
        if (id < 0) {
            if (crackStage == Byte.MAX_VALUE) {
                multimine$pos = pos;
                if (client.interactionManager != null) {((ObjIntConsumer<Object>) client.interactionManager).accept(pos,id);}
                return false;
            } else if (crackStage < 0 && pos.equals(multimine$pos)) multimine$pos = null;
        } return client.player == null || id != client.player.getId() || !pos.equals(multimine$pos);
    }
}