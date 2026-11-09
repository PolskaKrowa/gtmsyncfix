package dev.gtmsyncfix.mixin;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialEntry;
import dev.gtmsyncfix.SyncLock;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GTCEu fills ITEM_MATERIAL_ENTRY_COLLECTED / TAG_MATERIAL_ENTRY (plain fastutil open-hash maps) lazily from
 * whichever thread asks first. The render thread (renderContextAwareOutline) and the REI cache threads (tooltips)
 * do this concurrently; concurrent writes corrupt an open-addressing table so that get() probes forever.
 * <p>
 * Both overloads are serialised on one monitor. Implemented with plain Mixin (no MixinExtras): at HEAD, if this
 * thread does not already hold the lock, take it and re-invoke the method. The re-entrant call sees
 * {@link Thread#holdsLock} and falls through to the original body, which therefore runs under the lock. The
 * ItemLike overload calls the TagKey overload internally; that nested call also falls through.
 * <p>
 * getMaterial(Fluid), getItems and getBlocks follow the same lazy-fill-on-read pattern (FLUID_MATERIAL,
 * MATERIAL_ENTRY_ITEM_MAP, MATERIAL_ENTRY_BLOCK_MAP) and use the same one-statement guard.
 */
@Mixin(value = ChemicalHelper.class, remap = false)
public abstract class ChemicalHelperMixin {

    @Inject(method = "getMaterialEntry(Lnet/minecraft/world/level/ItemLike;)Lcom/gregtechceu/gtceu/api/data/chemical/material/stack/MaterialEntry;",
            at = @At("HEAD"), cancellable = true)
    private static void gtmsyncfix$item(ItemLike item, CallbackInfoReturnable<MaterialEntry> cir) {
        if (Thread.holdsLock(SyncLock.LOCK)) return; // already serialised: run the original body
        synchronized (SyncLock.LOCK) {
            SyncLock.noteActive();
            cir.setReturnValue(ChemicalHelper.getMaterialEntry(item)); // re-enters; falls through above
        }
    }

    @Inject(method = "getMaterialEntry(Lnet/minecraft/tags/TagKey;)Lcom/gregtechceu/gtceu/api/data/chemical/material/stack/MaterialEntry;",
            at = @At("HEAD"), cancellable = true)
    private static void gtmsyncfix$tag(TagKey<Item> tag, CallbackInfoReturnable<MaterialEntry> cir) {
        if (Thread.holdsLock(SyncLock.LOCK)) return;
        synchronized (SyncLock.LOCK) {
            SyncLock.noteActive();
            cir.setReturnValue(ChemicalHelper.getMaterialEntry(tag));
        }
    }

    @Inject(method = "getMaterial", at = @At("HEAD"), cancellable = true)
    private static void gtmsyncfix$fluid(Fluid fluid, CallbackInfoReturnable<Material> cir) {
        if (!Thread.holdsLock(SyncLock.LOCK)) synchronized (SyncLock.LOCK) { cir.setReturnValue(ChemicalHelper.getMaterial(fluid)); }
    }

    @Inject(method = "getItems", at = @At("HEAD"), cancellable = true)
    private static void gtmsyncfix$items(MaterialEntry entry, CallbackInfoReturnable<List<ItemLike>> cir) {
        if (!Thread.holdsLock(SyncLock.LOCK)) synchronized (SyncLock.LOCK) { cir.setReturnValue(ChemicalHelper.getItems(entry)); }
    }

    @Inject(method = "getBlocks", at = @At("HEAD"), cancellable = true)
    private static void gtmsyncfix$blocks(MaterialEntry entry, CallbackInfoReturnable<List<Block>> cir) {
        if (!Thread.holdsLock(SyncLock.LOCK)) synchronized (SyncLock.LOCK) { cir.setReturnValue(ChemicalHelper.getBlocks(entry)); }
    }
}
