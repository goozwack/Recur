package dev.recur.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpellcasterIllager.class)
public interface SpellcasterAccessor {
	@Accessor("DATA_SPELL_CASTING_ID")
	static EntityDataAccessor<Byte> recur$spell() { throw new AssertionError(); }
}
