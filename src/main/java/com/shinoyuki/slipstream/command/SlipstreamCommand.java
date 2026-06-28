package com.shinoyuki.slipstream.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shinoyuki.slipstream.telemetry.PacketCapture;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import com.shinoyuki.slipstream.telemetry.TelemetryReport;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public final class SlipstreamCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("slipstream")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("report").executes(SlipstreamCommand::report))
                .then(Commands.literal("reset").executes(SlipstreamCommand::reset))
                .then(Commands.literal("capture")
                        .then(Commands.literal("start")
                                .executes(c -> captureStart(c, 180))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 600))
                                        .executes(c -> captureStart(c, IntegerArgumentType.getInteger(c, "seconds")))))
                        .then(Commands.literal("stop").executes(SlipstreamCommand::captureStop))));
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        PacketTelemetry telemetry = PacketTelemetry.get();
        String text = TelemetryReport.render(telemetry);
        for (String row : text.split("\n")) {
            String line = row;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        Path file = TelemetryReport.writeToFile(telemetry);
        Path json = TelemetryReport.writeJsonToFile(telemetry);
        if (file != null) {
            ctx.getSource().sendSuccess(() -> Component.literal("[Slipstream] snapshot written to " + file
                    + (json != null ? " (+ .json)" : "")), false);
        }
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        PacketTelemetry.get().reset();
        ctx.getSource().sendSuccess(() -> Component.literal("[Slipstream] telemetry counters reset"), false);
        return 1;
    }

    private static int captureStart(CommandContext<CommandSourceStack> ctx, int seconds) {
        Path file = PacketCapture.start(seconds);
        if (file == null) {
            ctx.getSource().sendFailure(Component.literal("[Slipstream] capture already running or failed to start"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Slipstream] capture started (" + seconds + "s, auto-stop) -> " + file), true);
        return 1;
    }

    private static int captureStop(CommandContext<CommandSourceStack> ctx) {
        String summary = PacketCapture.stop();
        if (summary == null) {
            ctx.getSource().sendFailure(Component.literal("[Slipstream] no capture running"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[Slipstream] capture stopped: " + summary), true);
        return 1;
    }

    private SlipstreamCommand() {
    }
}
