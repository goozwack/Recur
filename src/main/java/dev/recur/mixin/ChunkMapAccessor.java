package dev.recur.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	/** Reads a chunk's saved data (upgraded to this version), as the game does when loading it. */
	/** Every entity players are kept up to date about, by its id. */
	@Accessor("entityMap")
	it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> recur$entityMap();

	@Invoker("readChunk")
	CompletableFuture<Optional<CompoundTag>> recur$readChunk(ChunkPos pos);
}
