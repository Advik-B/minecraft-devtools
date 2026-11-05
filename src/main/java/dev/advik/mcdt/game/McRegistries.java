package dev.advik.mcdt.game;

import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

//? if >=1.21 {
import net.minecraft.core.registries.BuiltInRegistries;
//?}

/** The built-in registries MCDT reads: {@code BuiltInRegistries} since 1.19.3, static fields on {@code Registry} before. */
public final class McRegistries {
	//? if >=1.21 {
	public static final DefaultedRegistry<Block> BLOCK = BuiltInRegistries.BLOCK;
	public static final DefaultedRegistry<Item> ITEM = BuiltInRegistries.ITEM;
	public static final DefaultedRegistry<EntityType<?>> ENTITY_TYPE = BuiltInRegistries.ENTITY_TYPE;
	public static final Registry<MobEffect> MOB_EFFECT = BuiltInRegistries.MOB_EFFECT;
	public static final Registry<MenuType<?>> MENU = BuiltInRegistries.MENU;
	public static final Registry<? extends Registry<?>> REGISTRY = BuiltInRegistries.REGISTRY;
	//?} else {
	/*public static final DefaultedRegistry<Block> BLOCK = Registry.BLOCK;
	public static final DefaultedRegistry<Item> ITEM = Registry.ITEM;
	public static final DefaultedRegistry<EntityType<?>> ENTITY_TYPE = Registry.ENTITY_TYPE;
	public static final Registry<MobEffect> MOB_EFFECT = Registry.MOB_EFFECT;
	public static final Registry<MenuType<?>> MENU = Registry.MENU;
	public static final Registry<? extends Registry<?>> REGISTRY = Registry.REGISTRY;
	*///?}

	private McRegistries() {
	}
}
