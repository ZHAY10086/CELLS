package com.cells.recipes.conditions;

import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;

import net.minecraftforge.common.crafting.IConditionFactory;
import net.minecraftforge.common.crafting.JsonContext;
import net.minecraftforge.fml.common.Loader;


/**
 * Recipe condition that checks if a mod is NOT loaded.
 * Inverse of {@code forge:mod_loaded}, enables recipes that should only
 * be available when an optional dependency is absent.
 * <p>
 * JSON usage:
 * <pre>
 * { "type": "cells:mod_not_loaded", "modid": "mekeng" }
 * </pre>
 */
public class ModNotLoadedCondition implements IConditionFactory {

    @Override
    public BooleanSupplier parse(JsonContext context, JsonObject json) {
        String modid = json.get("modid").getAsString();
        return () -> !Loader.isModLoaded(modid);
    }
}
