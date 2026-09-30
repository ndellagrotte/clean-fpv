package io.github.ndellagrotte.cleanfpv.server.race;

import com.mojang.authlib.GameProfile;
import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.GateShapes;
import io.github.ndellagrotte.cleanfpv.common.race.LapTimes;
import io.github.ndellagrotte.cleanfpv.common.race.Leaderboard;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /cleanfpv race <subcommand>}: builds tracks and lets players race (spec §9 behaviour; the
 * original left all of this to a missing companion server mod).
 *
 * <pre>
 * list                                        tracks, gate counts, racers, best lap
 * top &lt;track&gt;                                 best laps
 * join &lt;track&gt; | leave                         race on a track (players)
 * mode [on|off] [track]                       show a track's gates (players; toggles without on/off)
 * create &lt;name&gt; | delete &lt;track&gt;               (op)
 * addgate &lt;track&gt; &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; [x|y|z] (op) gate from two corners; axis = flight axis
 * removegate &lt;track&gt; [number]                (op) default: the last gate
 * reset &lt;track&gt;                               (op) clears the leaderboard
 * </pre>
 *
 * Gate numbers shown to users are 1-based (gate 1 is start/finish). The root command is usable by
 * everyone; editing subcommands check permission level 2 themselves ({@link #checkPermission} is
 * overridden because vanilla denies every non-op player any command but a few built-ins).
 * Messages are translation keys ({@code cleanfpv.race.cmd.*}), rendered by the client's lang file.
 */
final class RaceCommand extends CommandBase {

    private static final String ROOT = "cleanfpv";
    private static final String KEY = "cleanfpv.race.cmd.";
    private static final int OP_LEVEL = 2;
    private static final List<String> SUBCOMMANDS = List.of(
            "list", "top", "join", "leave", "mode", "create", "delete", "addgate", "removegate", "reset");

    private final RaceServer races;

    RaceCommand(RaceServer races) {
        this.races = races;
    }

    @Override
    public String getName() {
        return ROOT;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return KEY + "usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2 || !"race".equalsIgnoreCase(args[0])) {
            throw new WrongUsageException(getUsage(sender));
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "top" -> top(server, sender, track(args, 2));
            case "join" -> join(sender, track(args, 2));
            case "leave" -> leave(sender);
            case "mode" -> mode(sender, args);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, track(args, 2));
            case "addgate" -> addGate(sender, args);
            case "removegate" -> removeGate(sender, args);
            case "reset" -> reset(sender, track(args, 2));
            default -> throw new WrongUsageException(getUsage(sender));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Subcommands

    private void list(ICommandSender sender) {
        List<TrackDef> tracks = new ArrayList<>(races.store().tracks());
        if (tracks.isEmpty()) {
            reply(sender, "list_empty");
            return;
        }
        reply(sender, "list_header", tracks.size());
        for (TrackDef t : tracks) {
            List<Leaderboard.Entry> top = races.store().board(t.id).top(1);
            String best = top.isEmpty() ? "-" : LapTimes.format(top.get(0).lapMs());
            reply(sender, "list_entry", t.name, t.gates.size(), races.racerCount(t.id), best);
        }
    }

    private void top(MinecraftServer server, ICommandSender sender, TrackDef track) {
        List<Leaderboard.Entry> rows = races.store().board(track.id).top(Leaderboard.SHOWN);
        if (rows.isEmpty()) {
            reply(sender, "top_empty", track.name);
            return;
        }
        reply(sender, "top_header", track.name);
        int rank = 1;
        for (Leaderboard.Entry e : rows) {
            reply(sender, "top_entry", rank++, name(server, e.playerId()), LapTimes.format(e.lapMs()));
        }
    }

    private void join(ICommandSender sender, TrackDef track) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        races.join(player, track);
        reply(sender, track.gates.isEmpty() ? "joined_empty" : "joined", track.name);
    }

    private void leave(ICommandSender sender) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        TrackDef left = races.leave(player);
        if (left == null) {
            throw new CommandException(KEY + "not_racing");
        }
        reply(sender, "left", left.name);
    }

    private void mode(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        // "mode" toggles, "mode on|off" sets, "mode [on] <track>" shows that track.
        boolean on = !races.isRaceMode(player.getUniqueID());
        int next = 2;
        if (args.length > next && ("on".equalsIgnoreCase(args[next]) || "off".equalsIgnoreCase(args[next]))) {
            on = "on".equalsIgnoreCase(args[next]);
            next++;
        } else if (args.length > next) {
            on = true;
        }
        if (args.length > next) {
            races.focus(player.getUniqueID(), track(args, next));
        }
        if (!on) {
            races.setRaceMode(player, false);
            reply(sender, "mode_off");
            return;
        }
        TrackDef shown = races.setRaceMode(player, true);
        if (shown == null) {
            reply(sender, "mode_on_none");
        } else {
            reply(sender, "mode_on", shown.name);
        }
    }

    private void create(ICommandSender sender, String[] args) throws CommandException {
        requireOp(sender);
        if (args.length < 3) {
            throw new WrongUsageException(getUsage(sender));
        }
        String name = args[2];
        if (!RaceStore.validName(name)) {
            throw new CommandException(KEY + "bad_name");
        }
        if (races.store().byName(name) != null) {
            throw new CommandException(KEY + "name_taken", name);
        }
        if (races.store().tracks().size() >= RaceStore.MAX_TRACKS) {
            throw new CommandException(KEY + "too_many_tracks", RaceStore.MAX_TRACKS);
        }
        TrackDef track = races.createTrack(name);
        if (sender instanceof EntityPlayerMP player) {
            races.focus(player.getUniqueID(), track);
        }
        reply(sender, "created", track.name);
    }

    private void delete(ICommandSender sender, TrackDef track) throws CommandException {
        requireOp(sender);
        races.deleteTrack(track);
        reply(sender, "deleted", track.name);
    }

    private void addGate(ICommandSender sender, String[] args) throws CommandException {
        requireOp(sender);
        if (args.length < 9) {
            throw new WrongUsageException(getUsage(sender));
        }
        TrackDef track = track(args, 2);
        if (track.gates.size() >= RaceStore.MAX_GATES) {
            throw new CommandException(KEY + "too_many_gates", track.name, RaceStore.MAX_GATES);
        }
        BlockPos a = parseBlockPos(sender, args, 3, false);
        BlockPos b = parseBlockPos(sender, args, 6, false);
        int axis = GateShapes.AUTO;
        if (args.length > 9) {
            axis = switch (args[9].toLowerCase(Locale.ROOT)) {
                case "x" -> 0;
                case "y" -> 1;
                case "z" -> 2;
                default -> throw new WrongUsageException(getUsage(sender));
            };
        }
        World world = sender.getEntityWorld();
        int dimension = world.provider.getDimension();
        GateShapes.Result result = GateShapes.fromCorners(dimension, a, b, axis,
                pos -> world.isBlockLoaded(pos)
                        && world.getBlockState(pos).getCollisionBoundingBox(world, pos) == null);
        if (result.problem() == GateShapes.Problem.TOO_LARGE) {
            throw new CommandException(KEY + "gate_too_large", GateShapes.MAX_EXTENT);
        }
        if (result.problem() == GateShapes.Problem.NO_OPENING) {
            throw new CommandException(KEY + "gate_no_opening");
        }
        GateDef gate = result.gate();
        TrackDef updated = races.addGate(track, gate);
        if (sender instanceof EntityPlayerMP player) {
            races.focus(player.getUniqueID(), updated);
        }
        int open = 0;
        for (int j = 0; j < gate.rowCount(); j++) {
            open += gate.rowStart(j) <= gate.rowEnd(j) ? 1 : 0;
        }
        reply(sender, "gate_added", updated.gates.size(), updated.name, open);
    }

    private void removeGate(ICommandSender sender, String[] args) throws CommandException {
        requireOp(sender);
        TrackDef track = track(args, 2);
        if (track.gates.isEmpty()) {
            throw new CommandException(KEY + "bad_gate", track.name, 1);
        }
        int number = track.gates.size();
        if (args.length > 3) {
            number = parseInt(args[3]);
            if (number < 1 || number > track.gates.size()) {
                throw new CommandException(KEY + "bad_gate", track.name, number);
            }
        }
        TrackDef updated = races.removeGate(track, number - 1);
        reply(sender, "gate_removed", number, updated.name);
    }

    private void reset(ICommandSender sender, TrackDef track) throws CommandException {
        requireOp(sender);
        races.resetBoard(track);
        reply(sender, "reset", track.name);
    }

    // ---------------------------------------------------------------------------------------------
    // Tab completion

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "race");
        }
        if (!"race".equalsIgnoreCase(args[0])) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        races.store().tracks().forEach(t -> names.add(t.name));
        switch (sub) {
            case "top", "join", "delete", "removegate", "reset" -> {
                return args.length == 3 ? getListOfStringsMatchingLastWord(args, names) : Collections.emptyList();
            }
            case "mode" -> {
                if (args.length == 3) {
                    List<String> options = new ArrayList<>(List.of("on", "off"));
                    options.addAll(names);
                    return getListOfStringsMatchingLastWord(args, options);
                }
                return args.length == 4 ? getListOfStringsMatchingLastWord(args, names) : Collections.emptyList();
            }
            case "addgate" -> {
                if (args.length == 3) {
                    return getListOfStringsMatchingLastWord(args, names);
                }
                if (args.length <= 6) {
                    return getTabCompletionCoordinate(args, 3, targetPos);
                }
                if (args.length <= 9) {
                    return getTabCompletionCoordinate(args, 6, targetPos);
                }
                return args.length == 10 ? getListOfStringsMatchingLastWord(args, "x", "y", "z") : Collections.emptyList();
            }
            default -> {
                return Collections.emptyList();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private TrackDef track(String[] args, int index) throws CommandException {
        if (args.length <= index) {
            throw new WrongUsageException(KEY + "usage");
        }
        TrackDef track = races.store().byName(args[index]);
        if (track == null) {
            throw new CommandException(KEY + "unknown_track", args[index]);
        }
        return track;
    }

    private void requireOp(ICommandSender sender) throws CommandException {
        if (!sender.canUseCommand(OP_LEVEL, getName())) {
            throw new CommandException(KEY + "no_permission");
        }
    }

    private static String name(MinecraftServer server, UUID id) {
        EntityPlayerMP online = server.getPlayerList().getPlayerByUUID(id);
        if (online != null) {
            return online.getName();
        }
        GameProfile cached = server.getPlayerProfileCache().getProfileByUUID(id);
        return cached != null && cached.getName() != null ? cached.getName() : id.toString().substring(0, 8);
    }

    private static void reply(ICommandSender sender, String key, Object... args) {
        sender.sendMessage(new TextComponentTranslation(KEY + key, args));
    }
}
