package dev.recur.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractWindCharge.class)
public interface WindChargeAccessor {
	@Invoker("explode")
	void recur$explode(Vec3 pos);
}
