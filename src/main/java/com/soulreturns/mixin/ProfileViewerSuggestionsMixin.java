package com.soulreturns.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import com.soulreturns.features.profileviewer.SpvCommand;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(CommandSuggestions.class)
public abstract class ProfileViewerSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Shadow private ParseResults<ClientSuggestionProvider> currentParse;

    @ModifyExpressionValue(method = "updateCommandInfo", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;getCommands()Lcom/mojang/brigadier/CommandDispatcher;"))
    private CommandDispatcher<ClientSuggestionProvider> soul$profileDispatcher(CommandDispatcher<ClientSuggestionProvider> original) {
        CommandDispatcher<ClientSuggestionProvider> selected = SpvCommand.suggestionDispatcher(original, input.getValue());
        if (currentParse != null && currentParse.getContext().getRootNode() != selected.getRoot()) currentParse = null;
        return selected;
    }

    @WrapOperation(method = "updateCommandInfo", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> soul$profileRootSuggestion(CommandDispatcher<ClientSuggestionProvider> dispatcher, ParseResults<ClientSuggestionProvider> parse, int cursor, Operation<CompletableFuture<Suggestions>> original) {
        return SpvCommand.completePvRoot(original.call(dispatcher, parse, cursor), parse, cursor);
    }
}
