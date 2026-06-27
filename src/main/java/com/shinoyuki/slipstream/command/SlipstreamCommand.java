package com.shinoyuki.slipstream.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
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
                .then(Commands.literal("reset").executes(SlipstreamCommand::reset)));
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        PacketTelemetry telemetry = PacketTelemetry.get();
        String text = TelemetryReport.render(telemetry);
        for (String row : text.split("\n")) {
            String line = row;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        Path file = TelemetryReport.writeToFile(telemetry);
        if (file != null) {
            ctx.getSource().sendSuccess(() -> Component.literal("[Slipstream] snapshot written to " + file), false);
        }
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        PacketTelemetry.get().reset();
        ctx.getSource().sendSuccess(() -> Component.literal("[Slipstream] telemetry counters reset"), false);
        return 1;
    }

    private SlipstreamCommand() {
    }
}
