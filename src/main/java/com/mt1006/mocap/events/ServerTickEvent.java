package com.mt1006.mocap.events;

import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.recording.Recording;
import net.minecraft.server.MinecraftServer;

public class ServerTickEvent
{
	public static void onEndTick(MinecraftServer server)
	{
		// While ticks are frozen (/tick freeze) playback and recording wait too, so they stay in step with the world.
		if (!server.tickRateManager().runsNormally()) return;
		Recording.onTick();
		dev.recur.MobHistory.tick(server);
		Playing.onTick();
	}
}
