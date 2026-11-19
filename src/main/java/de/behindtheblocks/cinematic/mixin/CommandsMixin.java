package de.behindtheblocks.cinematic.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.behindtheblocks.cinematic.scene.SceneEditor;
import de.behindtheblocks.cinematic.studio.Suggest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Live scene recording: every command a player runs passes {@code Commands.performCommand}
 * (typed, Director buttons and cue keys alike). Target: {@code Commands.performCommand(ParseResults, String)} (26.3).
 *
 * <p>Also: understandable errors for {@code /btb} commands. Instead of Minecraft's "Incorrect argument
 * for command" the player reads what is missing or wrong, what would fit there and – for typos – the
 * closest word ("meintest du „restart“?").
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void btb$recordForScene(ParseResults<CommandSourceStack> command, String commandString, CallbackInfo ci) {
		SceneEditor editor = SceneEditor.instance();
		if (editor != null) {
			editor.onPlayerCommand(command, commandString);
		}
		String input = commandString.startsWith("/") ? commandString.substring(1) : commandString;
		if (!(input.equals("btb") || input.startsWith("btb "))) {
			return;
		}
		CommandContextBuilder<CommandSourceStack> ctx = command.getContext().getLastChild();
		boolean complete = command.getExceptions().isEmpty() && !command.getReader().canRead() && ctx.getCommand() != null;
		if (complete || ctx.getNodes().isEmpty()) {
			return;
		}
		String message = btb$explain(command, ctx);
		if (message != null) {
			command.getContext().getSource().sendFailure(Component.literal("[BTB] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal(message).withStyle(ChatFormatting.RED)));
			ci.cancel();
		}
	}

	private static String btb$explain(ParseResults<CommandSourceStack> pr, CommandContextBuilder<CommandSourceStack> ctx) {
		List<ParsedCommandNode<CommandSourceStack>> nodes = ctx.getNodes();
		CommandNode<CommandSourceStack> last = nodes.get(nodes.size() - 1).getNode();
		String done = pr.getReader().getString().substring(0, Math.min(pr.getReader().getString().length(),
				nodes.get(nodes.size() - 1).getRange().getEnd())).trim();
		String rest = pr.getReader().getRemaining().trim();
		List<String> words = new ArrayList<>();
		List<String> args = new ArrayList<>();
		for (CommandNode<CommandSourceStack> child : last.getChildren()) {
			if (!child.canUse(pr.getContext().getSource())) {
				continue;
			}
			if (child instanceof LiteralCommandNode<CommandSourceStack> l) {
				words.add(l.getLiteral());
			} else if (child instanceof ArgumentCommandNode<CommandSourceStack, ?> a) {
				args.add("<" + a.getName() + ">");
			}
		}
		if (words.isEmpty() && args.isEmpty()) {
			return null; // vanilla's own message is better here (e.g. a wrong number)
		}
		String options = String.join(", ", words.stream().limit(14).toList()) + (words.size() > 14 ? " …" : "")
				+ (args.isEmpty() ? "" : (words.isEmpty() ? "" : " oder ") + String.join(" / ", args));
		if (rest.isEmpty()) {
			return "/" + done + " – da fehlt noch etwas. Möglich: " + options;
		}
		String next = rest.split(" ")[0];
		if (!words.isEmpty() && args.isEmpty()) {
			return "„" + next + "“ gibt es bei /" + done + " nicht" + Suggest.hint(next, words) + " Möglich: " + options;
		}
		if (!pr.getExceptions().isEmpty()) {
			String why = pr.getExceptions().values().iterator().next().getMessage();
			return "/" + done + " " + next + " – " + why + ". Erwartet: " + options;
		}
		return "/" + done + " " + next + " – passt hier nicht. Erwartet: " + options;
	}
}
