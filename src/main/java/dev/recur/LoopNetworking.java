package dev.recur;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class LoopNetworking {
	private LoopNetworking() {}

	/** votes = how many online players currently want this checkpoint. star = position among starred ones (0 = not starred). */
	public record CheckpointInfo(int id, String name, String creator, long timelineTick, String dimension, int x, int y, int z, int votes, int star) {
		static void write(RegistryFriendlyByteBuf b, CheckpointInfo c) {
			b.writeInt(c.id); b.writeUtf(c.name); b.writeUtf(c.creator); b.writeLong(c.timelineTick); b.writeUtf(c.dimension);
			b.writeInt(c.x); b.writeInt(c.y); b.writeInt(c.z); b.writeInt(c.votes); b.writeInt(c.star);
		}
		static CheckpointInfo read(RegistryFriendlyByteBuf b) {
			return new CheckpointInfo(b.readInt(), b.readUtf(), b.readUtf(), b.readLong(), b.readUtf(),
				b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
		}
	}

	/**
	 * Server to client: everything the escape menu and HUD need.
	 * myVote / effectiveId: -1 means the loop start. effectiveId is what the next run will actually use.
	 */
	/** available = false: this world wasn't created with Recur, so Recur stays switched off here. */
	public record StatePayload(boolean available, boolean active, int myClones, int totalClones, int runNumber, int remainingTicks,
							   int myVote, int effectiveId, int players, int startVotes,
							   List<CheckpointInfo> checkpoints, int timedTicks) implements CustomPacketPayload {
		public static final Type<StatePayload> TYPE = new Type<>(Recur.id("state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.ofMember(StatePayload::write, StatePayload::read);

		void write(RegistryFriendlyByteBuf b) {
			b.writeBoolean(available); b.writeBoolean(active); b.writeInt(myClones); b.writeInt(totalClones); b.writeInt(runNumber); b.writeInt(remainingTicks);
			b.writeInt(myVote); b.writeInt(effectiveId); b.writeInt(players); b.writeInt(startVotes);
			b.writeInt(checkpoints.size());
			checkpoints.forEach(c -> CheckpointInfo.write(b, c));
			b.writeInt(timedTicks);
		}
		static StatePayload read(RegistryFriendlyByteBuf b) {
			boolean available = b.readBoolean();
			boolean active = b.readBoolean();
			int mine = b.readInt(), total = b.readInt(), run = b.readInt(), remaining = b.readInt();
			int vote = b.readInt(), effective = b.readInt(), players = b.readInt(), startVotes = b.readInt();
			int n = b.readInt();
			List<CheckpointInfo> list = new ArrayList<>();
			for (int i = 0; i < n; i++) list.add(CheckpointInfo.read(b));
			return new StatePayload(available, active, mine, total, run, remaining, vote, effective, players, startVotes, list, b.readInt());
		}
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server to client: start or end the world-reset animation. */
	public record ResetPayload(boolean start) implements CustomPacketPayload {
		public static final Type<ResetPayload> TYPE = new Type<>(Recur.id("reset"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResetPayload> CODEC =
			StreamCodec.ofMember((p, b) -> b.writeBoolean(p.start), b -> new ResetPayload(b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Server to client: these entities are clones (fadeIn = just spawned, so fade them in; false = already there). Each
	 * one comes with its age: the order the clones were made in (0 = the first clone of the loop, -1 = unknown).
	 */
	public record ClonesPayload(java.util.List<Integer> ids, java.util.List<Integer> ages, boolean fadeIn) implements CustomPacketPayload {
		public static final Type<ClonesPayload> TYPE = new Type<>(Recur.id("clones"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClonesPayload> CODEC = StreamCodec.ofMember((p, b) -> {
			b.writeVarInt(p.ids.size());
			for (int i = 0; i < p.ids.size(); i++) { b.writeVarInt(p.ids.get(i)); b.writeVarInt(p.ages.get(i) + 1); }
			b.writeBoolean(p.fadeIn);
		}, b -> {
			int n = b.readVarInt();
			java.util.List<Integer> ids = new java.util.ArrayList<>(), ages = new java.util.ArrayList<>();
			for (int i = 0; i < n; i++) { ids.add(b.readVarInt()); ages.add(b.readVarInt() - 1); }
			return new ClonesPayload(ids, ages, b.readBoolean());
		});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client to server: create a checkpoint here, with a tiny screenshot of the creator's view. */
	public record CreatePayload(byte[] thumbnail) implements CustomPacketPayload {
		public static final Type<CreatePayload> TYPE = new Type<>(Recur.id("create"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CreatePayload> CODEC =
			StreamCodec.ofMember((p, b) -> b.writeByteArray(p.thumbnail), b -> new CreatePayload(b.readByteArray(16384)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client to server: rename a checkpoint. */
	public record RenamePayload(int id, String name) implements CustomPacketPayload {
		public static final Type<RenamePayload> TYPE = new Type<>(Recur.id("rename"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RenamePayload> CODEC =
			StreamCodec.ofMember((p, b) -> { b.writeInt(p.id); b.writeUtf(p.name, 64); }, b -> new RenamePayload(b.readInt(), b.readUtf(64)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server to client: a checkpoint's thumbnail. It is sent once and left out of the regular state updates. */
	public record ThumbPayload(int id, byte[] data) implements CustomPacketPayload {
		public static final Type<ThumbPayload> TYPE = new Type<>(Recur.id("thumb"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ThumbPayload> CODEC =
			StreamCodec.ofMember((p, b) -> { b.writeInt(p.id); b.writeByteArray(p.data); }, b -> new ThumbPayload(b.readInt(), b.readByteArray(16384)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server to client: a clone says something in chat (sent as a payload so each player can mute clone chat). */
	public record CloneChatPayload(String speaker, String text) implements CustomPacketPayload {
		public static final Type<CloneChatPayload> TYPE = new Type<>(Recur.id("clone_chat"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CloneChatPayload> CODEC =
			StreamCodec.ofMember((p, b) -> { b.writeUtf(p.speaker, 64); b.writeUtf(p.text, 512); }, b -> new CloneChatPayload(b.readUtf(64), b.readUtf(512)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static final int ACTION_CREATE = 0, ACTION_VOTE = 1, ACTION_DELETE = 2, ACTION_START = 3, ACTION_STAR = 4, ACTION_START_TIMED = 5, ACTION_CANCEL = 6;

	public static final int PROMPT_NAME = 0, PROMPT_ACCEPT = 1, PROMPT_TUTORIAL = 2, PROMPT_START_PICTURE = 3;

	/**
	 * Server to client: ask the player something. NAME: name the checkpoint you just made. ACCEPT: someone made a checkpoint,
	 * switch to it? TUTORIAL: first time in this world. START_PICTURE: take the loop start's picture (you started the loop).
	 */
	public record PromptPayload(int kind, int id, String name, String creator) implements CustomPacketPayload {
		public static final Type<PromptPayload> TYPE = new Type<>(Recur.id("prompt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PromptPayload> CODEC = StreamCodec.ofMember(
			(p, b) -> { b.writeInt(p.kind); b.writeInt(p.id); b.writeUtf(p.name, 64); b.writeUtf(p.creator, 64); },
			b -> new PromptPayload(b.readInt(), b.readInt(), b.readUtf(64), b.readUtf(64)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client to server: the starred checkpoints, top first (after dragging them around). */
	public record StarOrderPayload(List<Integer> ids) implements CustomPacketPayload {
		public static final Type<StarOrderPayload> TYPE = new Type<>(Recur.id("star_order"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StarOrderPayload> CODEC = StreamCodec.ofMember((p, b) -> {
			b.writeVarInt(p.ids.size());
			for (int id : p.ids) b.writeInt(id);
		}, b -> {
			int n = Math.min(b.readVarInt(), 4096);
			List<Integer> ids = new ArrayList<>();
			for (int i = 0; i < n; i++) ids.add(b.readInt());
			return new StarOrderPayload(ids);
		});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client to server: the picture for the loop start. */
	public record StartPicturePayload(byte[] thumbnail) implements CustomPacketPayload {
		public static final Type<StartPicturePayload> TYPE = new Type<>(Recur.id("start_picture"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StartPicturePayload> CODEC =
			StreamCodec.ofMember((p, b) -> b.writeByteArray(p.thumbnail), b -> new StartPicturePayload(b.readByteArray(16384)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server to client (timed resets): slots of the open container whose item a clone takes later. */
	public record LockedSlotsPayload(int containerId, int[] slots) implements CustomPacketPayload {
		public static final Type<LockedSlotsPayload> TYPE = new Type<>(Recur.id("locked_slots"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LockedSlotsPayload> CODEC = StreamCodec.ofMember(
			(p, b) -> { b.writeInt(p.containerId); b.writeVarIntArray(p.slots); },
			b -> new LockedSlotsPayload(b.readInt(), b.readVarIntArray(1024)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client to server: what the crosshair is on now (kind 0 = nothing, 1 = block pos, 2 = entity id). */
	public record LookPayload(int kind, long value) implements CustomPacketPayload {
		public static final Type<LookPayload> TYPE = new Type<>(Recur.id("look"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookPayload> CODEC =
			StreamCodec.ofMember((p, b) -> { b.writeVarInt(p.kind); b.writeLong(p.value); }, b -> new LookPayload(b.readVarInt(), b.readLong()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server to client: whether that thing gives you nothing (the crosshair turns red). */
	public record NoLootPayload(int kind, long value, boolean nothing) implements CustomPacketPayload {
		public static final Type<NoLootPayload> TYPE = new Type<>(Recur.id("no_loot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NoLootPayload> CODEC = StreamCodec.ofMember(
			(p, b) -> { b.writeVarInt(p.kind); b.writeLong(p.value); b.writeBoolean(p.nothing); },
			b -> new NoLootPayload(b.readVarInt(), b.readLong(), b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client to server: create a checkpoint for everyone, vote for one (-1 = loop start), or delete one. */
	public record ActionPayload(int action, int id) implements CustomPacketPayload {
		public static final Type<ActionPayload> TYPE = new Type<>(Recur.id("action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
			StreamCodec.ofMember((p, b) -> { b.writeInt(p.action); b.writeInt(p.id); }, b -> new ActionPayload(b.readInt(), b.readInt()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** A world's settings (see ServerSettings), and whether this player may change them. Sent both ways. */
	public record SettingsPayload(ServerSettings.Values values, boolean canEdit) implements CustomPacketPayload {
		public static final Type<SettingsPayload> TYPE = new Type<>(Recur.id("settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SettingsPayload> CODEC = StreamCodec.ofMember((p, b) -> {
			b.writeVarInt(p.values.loadPercent());
			b.writeBoolean(p.values.smartReset());
			b.writeVarInt(p.values.timedSeconds());
			b.writeBoolean(p.values.antiDupe());
			b.writeBoolean(p.values.clearBlocks());
			b.writeBoolean(p.values.cloneLoot());
			b.writeBoolean(p.values.loopAntiDupe());
			b.writeBoolean(p.canEdit);
		}, b -> new SettingsPayload(new ServerSettings.Values(b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean()), b.readBoolean()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** The settings screen opened before the world's settings arrived: send them now. */
	public record SettingsRequestPayload() implements CustomPacketPayload {
		public static final Type<SettingsRequestPayload> TYPE = new Type<>(Recur.id("settings_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SettingsRequestPayload> CODEC = StreamCodec.unit(new SettingsRequestPayload());
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static void sendSettings(ServerPlayer player, SettingsPayload payload) {
		if (ServerPlayNetworking.canSend(player, SettingsPayload.TYPE)) ServerPlayNetworking.send(player, payload);
	}

	/**
	 * Server to client: a sound or level event a clone makes (see SoundLog). It's sent as Recur's own packet so each
	 * player's game can play it at their clone action volume. Players without Recur get the normal packet instead.
	 */
	public record CloneEffectPayload(boolean levelEvent, String sound, int source, double x, double y, double z, float volume, float pitch,
									 long seed, int eventType, int data) implements CustomPacketPayload {
		public static final Type<CloneEffectPayload> TYPE = new Type<>(Recur.id("clone_effect"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CloneEffectPayload> CODEC = StreamCodec.ofMember((p, b) -> {
			b.writeBoolean(p.levelEvent);
			b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
			if (p.levelEvent) {
				b.writeInt(p.eventType); b.writeInt(p.data);
			} else {
				b.writeUtf(p.sound); b.writeVarInt(p.source); b.writeFloat(p.volume); b.writeFloat(p.pitch); b.writeLong(p.seed);
			}
		}, b -> {
			boolean levelEvent = b.readBoolean();
			double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
			if (levelEvent) return new CloneEffectPayload(true, "", 0, x, y, z, 0, 0, 0, b.readInt(), b.readInt());
			return new CloneEffectPayload(false, b.readUtf(), b.readVarInt(), x, y, z, b.readFloat(), b.readFloat(), b.readLong(), 0, 0);
		});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(CloneEffectPayload.TYPE, CloneEffectPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ResetPayload.TYPE, ResetPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ClonesPayload.TYPE, ClonesPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CreatePayload.TYPE, CreatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RenamePayload.TYPE, RenamePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ThumbPayload.TYPE, ThumbPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CloneChatPayload.TYPE, CloneChatPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PromptPayload.TYPE, PromptPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(StarOrderPayload.TYPE, StarOrderPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) ->
			context.server().execute(() -> ServerSettings.change(context.player(), payload.values())));
		PayloadTypeRegistry.serverboundPlay().register(SettingsRequestPayload.TYPE, SettingsRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SettingsRequestPayload.TYPE, (payload, context) ->
			context.server().execute(() -> ServerSettings.sync(context.player())));
		PayloadTypeRegistry.serverboundPlay().register(StartPicturePayload.TYPE, StartPicturePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LockedSlotsPayload.TYPE, LockedSlotsPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LookPayload.TYPE, LookPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NoLootPayload.TYPE, NoLootPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LookPayload.TYPE, (payload, context) -> context.server().execute(() -> {
			ServerPlayer p = context.player();
			boolean nothing = Reservations.noLoot(p, payload.kind(), payload.value());
			if (ServerPlayNetworking.canSend(p, NoLootPayload.TYPE))
				ServerPlayNetworking.send(p, new NoLootPayload(payload.kind(), payload.value(), nothing));
		}));
		ServerPlayNetworking.registerGlobalReceiver(StarOrderPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LoopManager.reorderStars(context.player(), payload.ids())));
		ServerPlayNetworking.registerGlobalReceiver(StartPicturePayload.TYPE, (payload, context) ->
			context.server().execute(() -> LoopManager.onStartPicture(context.player(), payload.thumbnail())));
		ServerPlayNetworking.registerGlobalReceiver(CreatePayload.TYPE, (payload, context) ->
			context.server().execute(() -> LoopManager.createCheckpoint(context.player(), payload.thumbnail())));
		ServerPlayNetworking.registerGlobalReceiver(RenamePayload.TYPE, (payload, context) ->
			context.server().execute(() -> LoopManager.rename(context.player(), payload.id(), payload.name())));

		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LoopManager.onAction(context.player(), payload.action(), payload.id())));
	}

	public static void sendState(ServerPlayer player, StatePayload payload) {
		if (ServerPlayNetworking.canSend(player, StatePayload.TYPE)) ServerPlayNetworking.send(player, payload);
	}

	/** True if this player's game has Recur installed (it can receive Recur's packets). */
	public static boolean hasRecur(ServerPlayer player) { return ServerPlayNetworking.canSend(player, StatePayload.TYPE); }

	public static void sendClones(ServerPlayer player, java.util.List<Integer> ids, java.util.List<Integer> ages, boolean fadeIn) {
		if (ServerPlayNetworking.canSend(player, ClonesPayload.TYPE)) ServerPlayNetworking.send(player, new ClonesPayload(ids, ages, fadeIn));
	}

	public static void sendThumb(ServerPlayer player, int id, byte[] data) {
		if (data != null && ServerPlayNetworking.canSend(player, ThumbPayload.TYPE)) ServerPlayNetworking.send(player, new ThumbPayload(id, data));
	}

	public static void sendPrompt(ServerPlayer player, int kind, int id, String name, String creator) {
		if (ServerPlayNetworking.canSend(player, PromptPayload.TYPE)) ServerPlayNetworking.send(player, new PromptPayload(kind, id, name, creator));
	}

	public static void sendLocked(ServerPlayer player, int containerId, int[] slots) {
		if (ServerPlayNetworking.canSend(player, LockedSlotsPayload.TYPE)) ServerPlayNetworking.send(player, new LockedSlotsPayload(containerId, slots));
	}

	public static void sendReset(ServerPlayer player, boolean start) {
		if (ServerPlayNetworking.canSend(player, ResetPayload.TYPE)) ServerPlayNetworking.send(player, new ResetPayload(start));
	}
}
