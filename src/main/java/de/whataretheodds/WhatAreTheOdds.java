package de.whataretheodds;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;

public class WhatAreTheOdds implements ModInitializer {
    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger("whataretheodds");
    // Map to store ongoing challenges
    private static final ArrayList<Challenge> challenges = new ArrayList<Challenge>() {
    };

    // Class to represent a challenge
    private static void startChallenge(ServerPlayerEntity sourcePlayer, ServerPlayerEntity targetPlayer, String challengeText) {
        Optional<Challenge> result = challenges.stream().filter(challenge -> challenge.targetPlayer == targetPlayer).findFirst();
        // remove challenge from array if it already exists. each player can only have one challange
        if (result.isPresent()) {
            challenges.remove(result.get());
        }
        Challenge challenge = new Challenge(sourcePlayer, targetPlayer, challengeText);
        challenges.add(challenge);

        // Send challenge message to players
        sourcePlayer.sendMessage(Title());
        sourcePlayer.sendMessage(RegularMessage(String.format("Du hast %s zu einer Odds herausgefordert. Dieser kann nun mit /odds respond <seine Odds> antworten", targetPlayer.getName().getString())));
        sourcePlayer.sendMessage(RegularMessage(String.format("\n%s", challengeText)));

        targetPlayer.sendMessage(Title());
        targetPlayer.sendMessage(RegularMessage(String.format("%s hat dich zu einer Odds herausgefordert. Du kannst mit /odds respond <deine Odds> antworten", sourcePlayer.getName().getString())));
        targetPlayer.sendMessage(RegularMessage(String.format("\n%s", challengeText)));
    }

    private static Text Title() {
        return Text.literal("==== Was sind die Odds? ====").formatted(Formatting.AQUA);
    }

    private static Text RegularMessage(String input) {
        return Text.literal(input).formatted(Formatting.GRAY);
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        // Register the "/whataretheodds" command
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            Optional<Challenge> result = challenges.stream().filter(challenge -> challenge.challengeActive && (sender == challenge.sourcePlayer || sender == challenge.targetPlayer)).findFirst();
            if (result.isPresent()) {
                try {
                    int parsedInt = Integer.parseInt(message.getContent().getString());
                    Challenge challenge = result.get();
                    if (parsedInt < 1 || parsedInt > result.get().limit) {
                        sender.sendMessage(RegularMessage(String.format("Du musst eine Zahl zwischen 1 und %s", challenge.limit)));
                        return false;
                    }
                    sender.sendMessage(RegularMessage(String.format("Deine Odds: %s", message.getContent().getString())));

                    if (sender == challenge.targetPlayer) {
                        challenge.targetPlayerGuess = OptionalInt.of(parsedInt);
                        challenge.check();
                    }
                    if (sender == challenge.sourcePlayer) {
                        challenge.sourcePlayerGuess = OptionalInt.of(parsedInt);
                        challenge.check();
                    }
                    return false;
                } catch (NumberFormatException e) {
                    sender.sendMessage(Text.literal(String.format("Du musst eine Zahl zwischen 1 und %s eingeben", result.get().limit)).formatted(Formatting.RED));
                }

            }
            return true;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, environment) ->
                dispatcher.register(CommandManager.literal("odds")
                        .then(CommandManager.literal("challenge").then(CommandManager.argument("target", EntityArgumentType.player())
                                .then(CommandManager.argument("text", StringArgumentType.string())
                                        .executes(context -> {
                                            ServerPlayerEntity sourcePlayer = context.getSource().getPlayer();
                                            ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");

                                            if (sourcePlayer != null && targetPlayer != null && !sourcePlayer.equals(targetPlayer)) {
                                                // Start a new challenge
                                                startChallenge(sourcePlayer, targetPlayer, StringArgumentType.getString(context, "text"));
                                            } else {
                                                context.getSource().sendError(Text.literal("Ungültige Spielerauswahl. Du kannst dich nicht selbst herausfordern"));
                                            }

                                            return 1;
                                        }))))
                        .then(CommandManager.literal("respond").then(CommandManager.argument("number", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            // search for the challenge
                                            Optional<Challenge> result = challenges.stream().filter(challenge -> challenge.targetPlayer == context.getSource().getPlayer()).findFirst();
                                            if (result.isEmpty()) {
                                                context.getSource().sendError(Text.literal("Du hast keine aktiven Odds"));
                                                return 0;
                                            }
                                            Challenge res = result.get();
                                            int intArgument = IntegerArgumentType.getInteger(context, "number");
                                            res.setLimit(intArgument);
                                            res.sourcePlayer.sendMessage(Title());
                                            res.sourcePlayer.sendMessage(RegularMessage(String.format("%s hat die Odds akzeptiert", res.targetPlayer.getName().getString())));
                                            res.sourcePlayer.sendMessage(RegularMessage(String.format("\nSchreibe eine Zahl zwischen 1 und %s in den Chat", res.limit)));

                                            res.targetPlayer.sendMessage(Title());
                                            res.targetPlayer.sendMessage(RegularMessage("Du hast die Odds akzeptiert"));
                                            res.targetPlayer.sendMessage(RegularMessage(String.format("\nSchreibe eine Zahl zwischen 1 und %s", intArgument)));

                                            return 1;
                                        }))

                                .executes(context -> {
                                    context.getSource().sendError(Text.literal("Richtige Benutzung:"));
                                    context.getSource().sendError(Text.literal("/odds challenge <Spieler> <Text der Odds>"));
                                    context.getSource().sendError(Text.literal("/odds respond <deine Odds>"));
                                    return 0;
                                })
                        )
                ));

        LOGGER.info("What are the odds? wurde erfolgreich geladen");
    }

    private static class Challenge {
        private final ServerPlayerEntity sourcePlayer;
        private final ServerPlayerEntity targetPlayer;
        private final String challengeText;
        private OptionalInt sourcePlayerGuess;
        private OptionalInt targetPlayerGuess;
        private int limit;
        private boolean challengeActive;

        public Challenge(ServerPlayerEntity sourcePlayer, ServerPlayerEntity targetPlayer, String challengeText) {
            this.sourcePlayer = sourcePlayer;
            this.targetPlayer = targetPlayer;
            this.challengeText = challengeText;
            this.challengeActive = false;
        }

        public void setLimit(int i) {
            this.challengeActive = true;
            this.limit = i;
        }

        public void check() {
            if (this.sourcePlayerGuess.isPresent() && this.targetPlayerGuess.isPresent()) {
                if (this.sourcePlayerGuess.getAsInt() == this.targetPlayerGuess.getAsInt()) {
                    this.sourcePlayer.sendMessage(Title());
                    this.sourcePlayer.sendMessage(Text.literal(String.format("%s muss die Odds machen!", this.targetPlayer.getName().getString())).formatted(Formatting.GREEN));
                    this.sourcePlayer.sendMessage(RegularMessage(String.format("\n%s", this.challengeText)));

                    this.targetPlayer.sendMessage(Title());
                    this.targetPlayer.sendMessage(Text.literal("Du musst die Odds machen!").formatted(Formatting.RED));
                    this.targetPlayer.sendMessage(RegularMessage(String.format("\n%s", this.challengeText)));
                    this.challengeActive = false;

                } else if (this.sourcePlayerGuess.getAsInt() + this.targetPlayerGuess.getAsInt() == limit) {
                    this.sourcePlayer.sendMessage(Title());
                    this.sourcePlayer.sendMessage(Text.literal("Beide müssen die Odds machen").formatted(Formatting.RED));
                    this.sourcePlayer.sendMessage(Text.literal(String.format("\n%s", this.challengeText)).formatted(Formatting.GRAY));

                    this.targetPlayer.sendMessage(Title());
                    this.targetPlayer.sendMessage(Text.literal("Beide müssen die Odds machen").formatted(Formatting.RED));
                    this.targetPlayer.sendMessage(Text.literal(String.format("\n%s", this.challengeText)).formatted(Formatting.GRAY));
                    this.challengeActive = false;

                } else if ((this.sourcePlayerGuess.getAsInt() == 1 && this.targetPlayerGuess.getAsInt() == limit)
                        || (this.targetPlayerGuess.getAsInt() == 1 && this.sourcePlayerGuess.getAsInt() == limit)) {
                    this.targetPlayer.sendMessage(Title());
                    this.targetPlayer.sendMessage(Text.literal(String.format("%s muss die Odds machen!", this.sourcePlayer.getName().getString())).formatted(Formatting.GREEN));
                    this.targetPlayer.sendMessage(RegularMessage(String.format("\n%s", this.challengeText)));

                    this.sourcePlayer.sendMessage(Title());
                    this.sourcePlayer.sendMessage(Text.literal("Du hast die Odds verloren. Du musst die Odds machen!").formatted(Formatting.RED));
                    this.sourcePlayer.sendMessage(RegularMessage(String.format("\n%s", this.challengeText)));
                    this.challengeActive = false;

                } else {
                    this.sourcePlayer.sendMessage(Title());
                    this.sourcePlayer.sendMessage(Text.literal("Nichts passiert").formatted(Formatting.YELLOW));
                    this.sourcePlayer.sendMessage(RegularMessage(String.format("\nDeine Odds waren: %s", this.sourcePlayerGuess.getAsInt())));
                    this.sourcePlayer.sendMessage(RegularMessage(String.format("%s Odds waren: %s", this.targetPlayer.getName().getString(), this.targetPlayerGuess.getAsInt())));

                    this.targetPlayer.sendMessage(Title());
                    this.targetPlayer.sendMessage(Text.literal("Nichts passiert").formatted(Formatting.YELLOW));
                    this.targetPlayer.sendMessage(RegularMessage(String.format("\nDeine Odds waren: %s", this.targetPlayerGuess.getAsInt())));
                    this.targetPlayer.sendMessage(RegularMessage(String.format("%s Odds waren: %s", this.sourcePlayer.getName().getString(), this.sourcePlayerGuess.getAsInt())));
                    this.challengeActive = false;
                }

            }
        }
    }
}