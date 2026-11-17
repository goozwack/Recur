package com.mt1006.mocap.mocap.actions;

import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface Action
{
	List<Function<RecordingFiles.Reader, Action>> REGISTERED = new ArrayList<>();

	Result execute(PlayingContext ctx);

	static void init()
	{
		if (REGISTERED.size() == 0)
		{
			for (Type type : Type.values()) { type.init(); }
		}
	}

	static Action readAction(RecordingFiles.Reader reader)
	{
		Function<RecordingFiles.Reader, Action> constructor = REGISTERED.get(reader.readByte());
		return constructor != null ? constructor.apply(reader) : null;
	}

	enum Type
	{
		NEXT_TICK(0, NextTick::new),
		MOVEMENT(1, Movement::new, Movement::new),
		HEAD_ROTATION(2, HeadRotation::new, HeadRotation::new),
		CHANGE_POSE(3, ChangePose::new, ChangePose::new),
		CHANGE_ITEM(4, ChangeItem::new, ChangeItem::new),
		SET_ENTITY_FLAGS(5, SetEntityFlags::new, SetEntityFlags::new),
		SET_LIVING_ENTITY_FLAGS(6, SetLivingEntityFlags::new, SetLivingEntityFlags::new),
		SET_MAIN_HAND(7, SetMainHand::new, SetMainHand::new),
		SWING(8, Swing::new, Swing::new),
		BREAK_BLOCK(9, BreakBlock::new),
		PLACE_BLOCK(10, PlaceBlock::new),
		RIGHT_CLICK_BLOCK(11, RightClickBlock::new),
		SET_EFFECT_COLOR(12, SetEffectColor::new, SetEffectColor::new),
		SET_ARROW_COUNT(13, SetArrowCount::new, SetArrowCount::new),
		SLEEP(14, Sleep::new, Sleep::new),
		PLACE_BLOCK_SILENTLY(15, PlaceBlockSilently::new),
		ENTITY_UPDATE(16, EntityUpdate::new),
		ENTITY_ACTION(17, EntityAction::new),
		HURT(18, Hurt::new),
		VEHICLE_DATA(19, VehicleData::new, VehicleData::new),
		BREAK_BLOCK_PROGRESS(20, BreakBlockProgress::new),
		SET_MOB_FLAGS(21, SetMobFlags::new, SetMobFlags::new),
		SET_DRAGON_PHASE(22, SetDragonPhase::new, SetDragonPhase::new),
		// Smaller forms of the actions written most, for entities other than the clone itself (see EntityAction)
		MOVEMENT_COMPACT(23, Movement::compact, true),
		HEAD_ROTATION_COMPACT(24, HeadRotation::compact, true),
		ENTITY_ACTION_COMPACT(25, EntityAction::compact),
		SET_INVENTORY(26, SetInventory::new, SetInventory::new),
		SET_BABY(27, SetBaby::new, SetBaby::new),
		SET_HEALTH(28, SetHealth::new, SetHealth::new),
		SET_EXPERIENCE(29, SetExperience::new, SetExperience::new);

		public final byte id;
		public final Function<RecordingFiles.Reader, Action> fromReader;
		public final Function<Entity, ComparableAction> fromEntity;

		Type(int id, Function<RecordingFiles.Reader, Action> fromReader)
		{
			this.id = (byte)id;
			this.fromReader = fromReader;
			this.fromEntity = null;

			if (fromReader.apply(RecordingFiles.DummyReader.READER) instanceof ComparableAction)
			{
				throw new RuntimeException("Tried to register ComparableAction without \"fromEntity\" constructor!");
			}
			registerAction();
		}

		/** Another way of storing an action that's already registered: read only, never made from an entity. */
		Type(int id, Function<RecordingFiles.Reader, Action> fromReader, boolean otherForm)
		{
			this.id = (byte)id;
			this.fromReader = fromReader;
			this.fromEntity = null;
			registerAction();
		}

		Type(int id, Function<RecordingFiles.Reader, Action> fromReader, Function<Entity, ComparableAction> fromEntity)
		{
			this.id = (byte)id;
			this.fromReader = fromReader;
			this.fromEntity = fromEntity;

			if (fromEntity == null) { throw new NullPointerException(); }
			ComparableAction.REGISTERED.add(fromEntity);
			registerAction();
		}

		private void registerAction()
		{
			if (REGISTERED.size() != id)
			{
				throw new RuntimeException("Tried to register Action with id out of order!");
			}
			REGISTERED.add(fromReader);
		}

		public void init() { /* dummy */ }
	}

	enum Result
	{
		OK(false, false),
		IGNORED(false, false),
		NEXT_TICK(true, false),
		END(true, true),
		ERROR(true, true);

		public final boolean endsTick;
		public final boolean endsPlayback;

		Result(boolean endsTick, boolean endsPlayback)
		{
			this.endsTick = endsTick;
			this.endsPlayback = endsPlayback;
		}
	}
}
