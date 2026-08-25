package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.CropFeverManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import it.unimi.dsi.fastutil.ints.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// opportunistically suck pests that enter the player's reach while doing the
// funny thing
public final class PestOnTheTrackManager {
	private static final PestOnTheTrackManager INSTANCE = new PestOnTheTrackManager();
	
	public static final double RANGE_SAFETY_MARGIN = 0.5;
	public static final long NO_TARGET_GRACE_MS = 500L;
	public static final int AIM_REFRESH_MS = 120;
	
	private volatile State state = State.IDLE;
	private Entity trackedPest;
	private long candidateSelectedAt;
	private long foundSuckTargetAt;
	private long noneSuckTargetSince;
	private int vacuumSlot = -1;
	private double vacuumRange;
	// this is for the restore thingy
	private boolean hasPreSuckRotation;
	private float preSuckYaw;
	private float preSuckPitch;
	private volatile boolean mousematWorkerPending;
	private volatile long currentRestoreAttemptId;
	// local record; sync with aether
	private final IntSet accountedKills = new IntOpenHashSet(); 

	private PestOnTheTrackManager() {}

	public static PestOnTheTrackManager getInstance() {
		return INSTANCE;
	}
	
	public void update(Minecraft client) {
		// prevent it from getting stick on the player state
		if (client == null || client.player == null || client.level == null || client.options == null) {
			this.resetOTTState(client, false); // this does the cleanup so the tool may not even exist 
			return;
		}
		
		// the user suddenly stop the macro, ts should stop too
		if (state != State.IDLE && !canRemainActive(client)) {
			if (state == State.SUCKING_CANDIDATE) {
				this.resetOTTState(client, true); // this requires extensive cleanups
			} else {
				this.clearOTTState(); // std one will do
			}
			return;
		}
		
		switch (state) {
			case IDLE -> this.updateWhileIdle(client);
			case PREPARING_TO_SUCK -> this.updateWhilePreparing(client);
			case SUCKING_CANDIDATE -> this.updateWhileSucking(client);
			case RESTORING -> this.updateWhileRestoring(client);
		}
	}
	
	private void updateWhileIdle(Minecraft client) {
		if (!this.canStartOTT(client)) {
			return;
		}
		
		if (!this.requireAndUpdateVac(client)) {
			return;
		}
		
		// little context here: to "pretend" that we "see" the pest while farming
		// this selection only selects ones that you can reasonably "see"
		Entity found = this.selectEligiblePest(client, true);
		if (found == null) {
			return;
		}
		
		trackedPest = found;
		candidateSelectedAt = System.currentTimeMillis();
		state = State.PREPARING_TO_SUCK;
	}

	private void updateWhilePreparing(Minecraft client) {
		if (!this.canStartOTT(client) || !this.requireAndUpdateVac(client)) {
			this.clearOTTState();
			return;
		}
		
		// the about to be sucked pest could've moved and we lost things to suck
		Entity found = this.selectEligiblePest(client, true);
		if (found == null) {
			this.clearOTTState(); // gone
			return;
		}
		
		long now = System.currentTimeMillis();
		if (trackedPest == null || trackedPest.getId() != found.getId()) {
			// edge case: a new pest came closer; give up the old one and suck the new one
			trackedPest = found;
			candidateSelectedAt = now;
			return;
		}
		
		if (now - candidateSelectedAt >= AetherConfig.PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS.get()) {
			this.beginSuckingTheCandidate(client, now);
		}
	}
	
	private void updateWhileSucking(Minecraft client) {
		long now = System.currentTimeMillis();
		if (!this.requireAndUpdateVac(client)) {
			this.resetOTTState(client, true); // vaccum disappeared
			return;
		}
		
		this.accountForTheDeathOfPreviousTarget(client); // see if the last one is already dead and filters it out
		
		Entity found = selectEligiblePest(client, false);
		// the pest we are sucking, disappeared
		if (found == null) {
			ClientUtils.setKeyMappingState(client.options.keyUse, false); // witheld the inp
			if (noneSuckTargetSince == 0L) {
				noneSuckTargetSince = now;
			} else if (now - noneSuckTargetSince >= NO_TARGET_GRACE_MS) {
				// this is probably a worm, it dug in alreday
				// so give up on this turn
				this.resetOTTState(client, true);
			}
			return;
		}
		
		if (trackedPest == null || trackedPest.getId() != found.getId()) {
			// edge case: a new pest came closer; give up the old one and suck the new one
			trackedPest = found;
			foundSuckTargetAt = now;
		}
		noneSuckTargetSince = 0L; // we found one
		
		if (now - foundSuckTargetAt >= AetherConfig.PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS.get()) {
			ClientUtils.sendDebugMessage("[Pest-On-The-Track] Sucking timed out! Resuming farming...");
			this.resetOTTState(client, true);
			return;
		}
		
		// now, its finally sucking time
		MacroInput.releaseMovement(client); // stop moving
		MacroInput.setAttack(client.options.keyAttack, false); // stop breaking crops
		
		int selectedSlot = ((AccessorInventory) client.player.getInventory()).getSelected();
		if (selectedSlot != vacuumSlot) {
			// this could be a staffcheck or the user fiddling around
			ClientUtils.setKeyMappingState(client.options.keyUse, false); // stop RCing
			FailsafeManager.selectHotbarSlot(client, vacuumSlot);
			return;
		}
		
		Vec3 aimTarget = PestCombatCoordinator.buildCombatAimTarget(client, trackedPest);
		if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
			ClientUtils.setKeyMappingState(client.options.keyUse, false); // stop RCing
			return;
		}
		RotationManager.forceRotation(client, aimTarget, AIM_REFRESH_MS);
		ClientUtils.setKeyMappingState(client.options.keyUse, true);
	}
	
	private void beginSuckingTheCandidate(Minecraft client, long timestamp) {
		foundSuckTargetAt = timestamp;
		noneSuckTargetSince = 0L;
		accountedKills.clear(); // clean staye
		// save these 4 ltr use
		hasPreSuckRotation = true;
		preSuckYaw = client.player.getYRot();
		preSuckPitch = client.player.getXRot();
		state = State.SUCKING_CANDIDATE;
		
		// this makes sure the macro stops touching inputs
		// while we suck the candidate
		MacroInput.releaseMovement(client);
		MacroInput.setAttack(client.options.keyAttack, false);
		ClientUtils.setKeyMappingState(client.options.keyUse, false);
		FailsafeManager.selectHotbarSlot(client, vacuumSlot);
		ClientUtils.sendDebugMessage(
			"[Pest-On-The-Track] A pest entered vacuum range. Pausing farming input and preparing to suck"
		);
	}
	
	private void updateWhileRestoring(Minecraft client) {
		MacroInput.releaseMovement(client);
		MacroInput.setAttack(client.options.keyAttack, false);
		ClientUtils.setKeyMappingState(client.options.keyUse, false);
		if (this.mousematWorkerPending) {
			return; // finishFarmingRestore will be called by the thread
		}
		// finisheed rotation using normal method(s)
		if (!RotationManager.isRotating()) {
			finishFarmingRestore();
		}
	}
	
	private Entity selectEligiblePest(Minecraft client, boolean needsInFOV) {
		double effectiveRange = Math.max(0.0, vacuumRange - RANGE_SAFETY_MARGIN);
		return PestTargetTracker.selectClosestPestWithin(client, effectiveRange,
			needsInFOV ? target -> {
				return isWithinGrabFOV(client, target);
			} : null
		);
	}
	
	private boolean isWithinGrabFOV(Minecraft client, Entity target) {
		final Vec3 playerEyePos = client.player.getEyePosition();
		final Vec3 targetEyePos = target.position().add(
			0, target.getEyeHeight(target.getPose()), 0
		);
		final double deltaX = targetEyePos.x - playerEyePos.x,
			deltaZ = targetEyePos.z - playerEyePos.z
		;
		// atan2(z, x) = angle in rad
		float targetYaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0f;
		float yawOffset = Math.abs(Mth.wrapDegrees(targetYaw - client.player.getYRot()));
		return yawOffset <= AetherConfig.PEST_ON_THE_TRACK_FOV.get() * 0.5F;
	}
	
	private boolean requireAndUpdateVac(Minecraft client) {
		int foundSlot = PestLoadoutHelper.findVacuumHotbarSlot(client);
		if (foundSlot < 0) {
			if (state == State.SUCKING_CANDIDATE) {
				ClientUtils.sendMessage("\u00A7cOn-The-Track pest destroyer needs a vacuum in the hotbar!!", true);
			}
			return false;
		}
		vacuumSlot = foundSlot;
		vacuumRange = PestLoadoutHelper.detectVacuumRange(client, foundSlot);
		return true;
	}

	private boolean canStartOTT(Minecraft client) {
		return AetherConfig.PEST_ON_TRACK_ENABLED.get() 
			&& this.canRemainActive(client) 
			&& client.screen == null
			&& client.player.containerMenu == client.player.inventoryMenu // invopen
			&& ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN 
			&& !PathfindingManager.isNavigating() 
			&& !AutoPestExchangeManager.isRunning()
			&& !AutoPestExchangeManager.shouldBlockFarmingResume() 
			&& !AutoSprayonatorManager.isRunning()
			&& !PestDestroyer.isActive()
			&& !PestManager.isCleaningInProgress()
			&& !LoadoutManager.isSwappingLoadout
			&& (!AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() || !CropFeverManager.isCropFeverActive)
			&& (!AetherConfig.PEST_ON_THE_TRACK_SKIP_JACOB.get() || ClientUtils.getJacobsContestRemainingMs() <= 0L)
		;
	}
	
	private boolean canRemainActive(Minecraft client) {
		return AetherConfig.PEST_ON_TRACK_ENABLED.get()
			&& MacroStateManager.getCurrentState() == MacroState.State.FARMING 
			&& FarmingMacroManager.isActive()
			&& client.getConnection() != null
		;
	}
	
	private void resetOTTState(Minecraft client, boolean restoreTool) {
		this.releaseOwnedInput(client); // return the control to normal systems
		if (restoreTool && client != null && client.player != null
			&& MacroStateManager.getCurrentState() == MacroState.State.FARMING 
			&& FarmingMacroManager.isActive()
		) {
			this.beginFarmingRestore(client);
			return;
		}
		this.clearOTTState();
		ClientUtils.sendDebugMessage("[Pest-On-The-Track] Resuming farming input...");
	}
	
	private void beginFarmingRestore(Minecraft client) {
		state = State.RESTORING;
		long restoreAttemptId = ++currentRestoreAttemptId;
		ClientUtils.sendDebugMessage("[Pest-On-The-Track] Restoring prior farming direction...");
		
		// mousemats are not synchronous, so we delegate this to other system and exit early
		if (AetherConfig.SQUEAKY_MOUSEMAT.get()) {
			ClientUtils.sendDebugMessage("[Pest-On-The-Track] Restoring direction using mousemat (id: " + restoreAttemptId + ")");
			mousematWorkerPending = true;
			// genuinely what the ehell is this
			MacroWorkerThread.getInstance().submit("POTT:MousematWorker", () -> {
				boolean hasRestored = false;
				try {
					// only attempt to restore if this attempt has not been 
					// overshadowed by another restore attempt
					if (state == State.RESTORING && currentRestoreAttemptId == restoreAttemptId) {
						hasRestored = SqueakyMousematManager.useIfNeeded(client);
						if (currentRestoreAttemptId == restoreAttemptId
							&& !MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)
						) {
							GearManager.swapToFarmingToolSync(client);
						}
					}
				} catch (Exception e) {
					ClientUtils.sendDebugMessage(
						"[Pest-On-The-Track] Mousemat restore exception: " + e.getMessage()
					);
				} finally {
					final boolean mousematOk = hasRestored;
					client.execute(() -> {
						if (state != State.RESTORING || currentRestoreAttemptId != restoreAttemptId) {
							return;
						}
						mousematWorkerPending = false;
						if (mousematOk) {
							this.finishFarmingRestore();
						} else {
							// mousemat fucked
							this.startFallbackRotation(client);
						}
					});
				}
			});
			return;
		}
		ClientUtils.sendDebugMessage("[Pest-On-The-Track] Swapping to the prev tool...");
		GearManager.swapToFarmingTool(client);
		ClientUtils.sendDebugMessage("[Pest-On-The-Track] Restoring direction using head rotation (id: " + restoreAttemptId + ")");
		this.startFallbackRotation(client);
	}
	
	private void startFallbackRotation(Minecraft client) {
		if (AetherConfig.MACRO_USE_CUSTOM_YAW.get() || AetherConfig.MACRO_USE_CUSTOM_PITCH.get()) {
			// best case non mousemat
			FarmingMacroManager.restoreConfiguredOrientation(client);  // this is async
			ClientUtils.sendDebugMessage("[Pest-On-The-Track] Using configured values to restore...");
		} else if (hasPreSuckRotation && client.player != null) {
			// the last case: user has no y/p, no mousmat, just do a best effort
			RotationManager.rotateToYawPitch( // this is async too
				client, preSuckYaw, preSuckPitch, 
				AetherConfig.ROTATION_TIME.get(), 
				true
			);
			ClientUtils.sendDebugMessage("[Pest-On-The-Track] Using pre-suck values to restore...");
		} else {
			// ??? case
			ClientUtils.sendMessage("\u00A7cUNABLE TO RESTORE ON-THE-TRACK FARMING DIRECTION! PLEASE REPORT THIS!", false);
			this.finishFarmingRestore();
		}
	}

	private void finishFarmingRestore() {
		this.clearOTTState();
		ClientUtils.sendDebugMessage("[Pest-On-The-Track] Farming direction restored. Resuming input...");
	}
	
	public void reset(Minecraft client) {
		this.resetOTTState(client, false);
	}
	
	private void releaseOwnedInput(Minecraft client) {
		if (client == null || client.options == null) {
			return;
		}
		ClientUtils.setKeyMappingState(client.options.keyUse, false);
		MacroInput.releaseMovement(client);
		MacroInput.setAttack(client.options.keyAttack, false);
		if (state == State.SUCKING_CANDIDATE) {
			RotationManager.cancelRotation();
		}
	}
	
	// basic cleanup without restoring side effects
	// DO NOT USE THIS IF YOU ARE RESUMING FROM ACTIVE STATE
	private void clearOTTState() {
		currentRestoreAttemptId++;
		state = State.IDLE;
		trackedPest = null;
		candidateSelectedAt = 0L;
		foundSuckTargetAt = 0L;
		noneSuckTargetSince = 0L;
		vacuumSlot = -1;
		vacuumRange = 0.0;
		mousematWorkerPending = false;
		accountedKills.clear();
		// this sucks, literally
		hasPreSuckRotation = false;
		preSuckYaw = 0.0f;
		preSuckPitch = 0.0f;
	}
	
	// due to the nature of this being a tick loop and not blocking linear exec
	// we do not know if the target is gone until the next tick
	private void accountForTheDeathOfPreviousTarget(Minecraft client) {
		if (trackedPest == null || !isDead(trackedPest) || accountedKills.contains(trackedPest.getId())) {
			return;
		}
		accountedKills.add(trackedPest.getId());
		PestManager.decrementPredictedAliveCount(client); // now i know
	}
	
	private boolean isDead(Entity entity) {
		return entity != null && (
			entity.isRemoved() || (entity instanceof LivingEntity living && living.isDeadOrDying())
		);
	}
	
	public boolean isNotIdle() {
		return state != State.IDLE;
	}
	
	// this will stop the tick() of the main farming loop to run while its taking over
	public boolean isBlockingFarming() {
		return state == State.SUCKING_CANDIDATE || state == State.RESTORING;
	}
	
	public static enum State {
		IDLE, PREPARING_TO_SUCK, SUCKING_CANDIDATE, RESTORING
	}
}
