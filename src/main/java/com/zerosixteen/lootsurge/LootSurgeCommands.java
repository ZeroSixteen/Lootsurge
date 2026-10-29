package com.zerosixteen.lootsurge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * /lootsurge help | list | give &lt;unique&gt; | test &lt;tier&gt; | reload | info.
 * "help", "list" and "info" are open to everyone; the rest need game-master (op) permission.
 */
final class LootSurgeCommands {
	private final LootEngine engine;

	LootSurgeCommands(LootEngine engine) {
		this.engine = engine;
	}

	void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("lootsurge")
				.requires(Commands.hasPermission(Commands.LEVEL_ALL))
				.executes(this::help)
				.then(Commands.literal("help").executes(this::help))
				.then(Commands.literal("list").executes(this::list))
				.then(Commands.literal("info").executes(this::info))
				.then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(this::reload))
				.then(Commands.literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("unique", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider
										.suggest(engine.uniqueEntries().keySet().stream(), builder))
								.executes(this::give)))
				.then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("tier", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider
										.suggest(Stream.of(Tier.values()).map(Tier::key), builder))
								.executes(this::test))));
	}

	private int help(CommandContext<CommandSourceStack> ctx) {
		String[] lines = {
				"Loot Surge by ZeroSixteen",
				"/lootsurge list - show every Unique item",
				"/lootsurge give <unique> - give yourself a Unique (op)",
				"/lootsurge test <tier> - spawn a test drop of a tier at your feet (op)",
				"/lootsurge reload - reload config and pools (op)",
				"/lootsurge info - status" };
		for (String line : lines) {
			String text = line;
			ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GOLD), false);
		}
		return 1;
	}

	private int info(CommandContext<CommandSourceStack> ctx) {
		String text = "Loot Surge: " + engine.summary();
		ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private int list(CommandContext<CommandSourceStack> ctx) {
		Map<String, LootEntry> uniques = engine.uniqueEntries();
		if (uniques.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No Uniques are loaded."));
			return 0;
		}
		ctx.getSource().sendSuccess(
				() -> Component.literal("Uniques (" + uniques.size() + "):").withStyle(ChatFormatting.GOLD), false);
		for (Map.Entry<String, LootEntry> e : uniques.entrySet()) {
			Component name = e.getValue().name != null ? e.getValue().name : Component.literal(e.getKey());
			Component line = Component.literal(" " + e.getKey() + " - ").withStyle(ChatFormatting.GRAY)
					.append(name);
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return uniques.size();
	}

	private int reload(CommandContext<CommandSourceStack> ctx) {
		engine.reload(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(
				() -> Component.literal("Loot Surge reloaded: " + engine.summary()).withStyle(ChatFormatting.GOLD),
				true);
		return 1;
	}

	private int give(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "unique").toLowerCase(Locale.ROOT);
		LootEntry entry = engine.uniqueEntries().get(id);
		if (entry == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown Unique '" + id + "'. Try /lootsurge list."));
			return 0;
		}
		ServerPlayer player;
		try {
			player = ctx.getSource().getPlayerOrException();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			ctx.getSource().sendFailure(Component.literal("Only a player can receive the item."));
			return 0;
		}
		ItemStack stack = entry.createShowcase();
		Component given = stack.getDisplayName();
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Gave you ").append(given), true);
		return 1;
	}

	private int test(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "tier");
		Tier tier = Tier.fromKey(name);
		if (tier == null) {
			List<String> keys = new ArrayList<>();
			for (Tier t : Tier.values()) {
				keys.add(t.key());
			}
			ctx.getSource().sendFailure(Component.literal("Unknown tier '" + name + "'. Use one of " + keys));
			return 0;
		}
		ServerPlayer player;
		try {
			player = ctx.getSource().getPlayerOrException();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			ctx.getSource().sendFailure(Component.literal("Run this as a player."));
			return 0;
		}
		String error = engine.testDrop(player, tier);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}
}
