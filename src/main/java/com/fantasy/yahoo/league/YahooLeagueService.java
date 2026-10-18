package com.fantasy.yahoo.league;

import com.fantasy.yahoo.league.dto.DraftStatus;
import com.fantasy.yahoo.league.dto.LeagueDraftPick;
import com.fantasy.yahoo.league.dto.LeagueDraftResponse;
import com.fantasy.yahoo.league.dto.LeagueDraftTeam;
import com.fantasy.yahoo.league.dto.LeagueRosterPlayer;
import com.fantasy.yahoo.league.dto.LeagueRosterTeam;
import com.fantasy.yahoo.league.dto.LeagueRostersResponse;
import com.fantasy.yahoo.league.dto.LeagueSettingsResponse;
import com.fantasy.yahoo.league.dto.LeagueSummary;
import com.fantasy.yahoo.league.dto.LeaguesResponse;
import com.fantasy.yahoo.league.dto.RosterSlot;
import com.fantasy.yahoo.league.dto.StatCategory;
import com.fantasy.yahoo.oauth.YahooOAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a user's NHL fantasy leagues and a league's settings from Yahoo, parsing Yahoo's
 * deeply-nested JSON (numeric-keyed objects mixed into arrays) into clean DTOs for the BFF.
 */
@Service
public class YahooLeagueService {

    private final YahooOAuthService oauthService;
    private final YahooFantasyClient client;

    public YahooLeagueService(YahooOAuthService oauthService, YahooFantasyClient client) {
        this.oauthService = oauthService;
        this.client = client;
    }

    public LeaguesResponse leagues(String appUserId) {
        JsonNode root = client.getUserNhlLeagues(oauthService.validAccessToken(appUserId));
        // fantasy_content.users.0.user[1].games.0.game[1].leagues
        JsonNode leaguesNode = root.path("fantasy_content").path("users").path("0").path("user")
                .path(1).path("games").path("0").path("game").path(1).path("leagues");

        List<LeagueSummary> leagues = new ArrayList<>();
        for (JsonNode entry : numericChildren(leaguesNode)) {
            JsonNode league = entry.path("league").path(0);
            if (league.isMissingNode() || league.path("league_key").isMissingNode()) {
                continue;
            }
            leagues.add(new LeagueSummary(
                    text(league, "league_key"),
                    text(league, "name"),
                    intOrNull(league, "season"),
                    intOrNull(league, "num_teams"),
                    text(league, "scoring_type")));
        }
        return new LeaguesResponse(leagues);
    }

    public LeagueSettingsResponse settings(String appUserId, String leagueKey) {
        JsonNode root = client.getLeagueSettings(oauthService.validAccessToken(appUserId), leagueKey);
        JsonNode leagueArray = root.path("fantasy_content").path("league");
        JsonNode meta = leagueArray.path(0);
        JsonNode settings = leagueArray.path(1).path("settings").path(0);

        return new LeagueSettingsResponse(
                text(meta, "league_key"),
                text(meta, "name"),
                firstNonBlank(text(settings, "scoring_type"), text(meta, "scoring_type")),
                parseStatCategories(settings),
                parseRosterPositions(settings),
                positiveIntOrNull(text(settings, "max_weekly_adds")));
    }

    public LeagueDraftResponse draft(String appUserId, String leagueKey) {
        JsonNode root = client.getLeagueDraft(oauthService.validAccessToken(appUserId), leagueKey);
        JsonNode leagueArray = root.path("fantasy_content").path("league");
        JsonNode meta = leagueArray.path(0);

        List<LeagueDraftPick> picks = parseDraftPicks(subresource(leagueArray, "draft_results"));
        DraftOrder order = inDraftOrder(parseDraftTeams(subresource(leagueArray, "teams")), picks);
        JsonNode settings = subresource(leagueArray, "settings").path(0);

        return new LeagueDraftResponse(
                firstNonBlank(text(meta, "league_key"), leagueKey),
                draftStatus(text(meta, "draft_status")),
                settings.path("is_auction_draft").asInt(0) == 1,
                order.teams(),
                order.known(),
                picks);
    }

    /**
     * Every team with the players it holds today, or with its lineup as set for {@code date} when
     * one is given. A player whose key carries no id is left out rather than guessed at, since an
     * id is all a consumer matches him by.
     */
    public LeagueRostersResponse rosters(String appUserId, String leagueKey, LocalDate date) {
        JsonNode root = client.getLeagueRosters(oauthService.validAccessToken(appUserId), leagueKey, date);
        JsonNode leagueArray = root.path("fantasy_content").path("league");
        List<LeagueRosterTeam> teams = new ArrayList<>();
        for (JsonNode entry : numericChildren(subresource(leagueArray, "teams"))) {
            JsonNode teamArray = entry.path("team");
            LeagueDraftTeam team = teamIdentity(teamArray.path(0));
            if (team == null) {
                continue;
            }
            teams.add(new LeagueRosterTeam(team.teamKey(), team.name(), team.mine(),
                    rosterPlayers(subresource(teamArray, "roster"))));
        }
        return new LeagueRostersResponse(firstNonBlank(text(leagueArray.path(0), "league_key"), leagueKey), teams);
    }

    /** Yahoo files a roster's players under its first numeric key, beside the roster's own coverage fields. */
    private static List<LeagueRosterPlayer> rosterPlayers(JsonNode roster) {
        List<LeagueRosterPlayer> players = new ArrayList<>();
        for (JsonNode block : numericChildren(roster)) {
            for (JsonNode entry : numericChildren(block.path("players"))) {
                LeagueRosterPlayer player = rosterPlayer(entry.path("player"));
                if (player != null) {
                    players.add(player);
                }
            }
        }
        return players;
    }

    private static LeagueRosterPlayer rosterPlayer(JsonNode playerArray) {
        String playerKey = null;
        String fullName = null;
        String teamAbbrev = null;
        Integer uniformNumber = null;
        boolean goalie = false;
        String status = null;
        List<String> eligiblePositions = new ArrayList<>();
        for (JsonNode attribute : playerArray.path(0)) {
            if (attribute.hasNonNull("player_key")) {
                playerKey = attribute.get("player_key").asText();
            }
            if (attribute.path("name").hasNonNull("full")) {
                fullName = attribute.path("name").get("full").asText();
            }
            if (attribute.hasNonNull("editorial_team_abbr")) {
                teamAbbrev = attribute.get("editorial_team_abbr").asText();
            }
            if (attribute.hasNonNull("uniform_number")) {
                uniformNumber = parseIntOrNull(attribute.get("uniform_number").asText());
            }
            if (attribute.hasNonNull("position_type")) {
                goalie = "G".equals(attribute.get("position_type").asText());
            }
            if (attribute.hasNonNull("status") && !attribute.get("status").asText().isBlank()) {
                status = attribute.get("status").asText();
            }
            for (JsonNode position : attribute.path("eligible_positions")) {
                if (position.hasNonNull("position")) {
                    eligiblePositions.add(position.get("position").asText());
                }
            }
        }
        Integer playerId = playerId(playerKey);
        if (playerId == null) {
            return null;
        }
        String selected = text(flatten(subresource(playerArray, "selected_position")), "position");
        return new LeagueRosterPlayer(playerKey, playerId, selected, fullName, teamAbbrev, uniformNumber, goalie,
                List.copyOf(eligiblePositions), status);
    }

    /** With {@code out=…} Yahoo lists each sub-resource as its own element after the metadata. */
    private static JsonNode subresource(JsonNode leagueArray, String name) {
        for (JsonNode element : leagueArray) {
            if (element.has(name)) {
                return element.get(name);
            }
        }
        return MissingNode.getInstance();
    }

    static DraftStatus draftStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return DraftStatus.UNKNOWN;
        }
        return switch (raw) {
            case "predraft" -> DraftStatus.PRE_DRAFT;
            case "postdraft" -> DraftStatus.FINISHED;
            default -> DraftStatus.IN_PROGRESS;
        };
    }

    private static List<LeagueDraftPick> parseDraftPicks(JsonNode draftResults) {
        List<LeagueDraftPick> picks = new ArrayList<>();
        for (JsonNode entry : numericChildren(draftResults)) {
            JsonNode result = flatten(entry.path("draft_result"));
            if (!result.hasNonNull("pick") || !result.hasNonNull("team_key")) {
                continue;
            }
            String playerKey = text(result, "player_key");
            picks.add(new LeagueDraftPick(
                    result.path("pick").asInt(),
                    result.path("round").asInt(0),
                    text(result, "team_key"),
                    playerKey == null || playerKey.isBlank() ? null : playerKey,
                    playerId(playerKey)));
        }
        picks.sort(Comparator.comparingInt(LeagueDraftPick::pick));
        return picks;
    }

    private static List<LeagueDraftTeam> parseDraftTeams(JsonNode teamsNode) {
        List<LeagueDraftTeam> teams = new ArrayList<>();
        for (JsonNode entry : numericChildren(teamsNode)) {
            LeagueDraftTeam team = teamIdentity(entry.path("team").path(0));
            if (team != null) {
                teams.add(team);
            }
        }
        return teams;
    }

    /** A team's key, name and ownership from its attribute list, or null when the key or name is missing. */
    private static LeagueDraftTeam teamIdentity(JsonNode attributes) {
        String teamKey = null;
        String name = null;
        boolean mine = false;
        for (JsonNode attribute : attributes) {
            if (attribute.hasNonNull("team_key")) {
                teamKey = attribute.get("team_key").asText();
            }
            if (attribute.hasNonNull("name")) {
                name = attribute.get("name").asText();
            }
            if (attribute.hasNonNull("is_owned_by_current_login")) {
                mine = attribute.get("is_owned_by_current_login").asInt(0) == 1;
            }
        }
        return teamKey != null && name != null ? new LeagueDraftTeam(teamKey, name, mine) : null;
    }

    /**
     * The teams, and whether their order is the draft's.
     *
     * @param known true only when every team was placed by a first-round slot of its own; a team
     *     without one (a league whose slots are not listed yet, or one that traded its first-round
     *     pick away) sits where Yahoo happened to list it, and its place says nothing
     */
    private record DraftOrder(List<LeagueDraftTeam> teams, boolean known) {
    }

    /**
     * Teams in the order they pick in the first round, where Yahoo lists the draft's slots. Before a
     * draft starts it lists none, and the teams keep Yahoo's own order, which is not a draft order.
     */
    private static DraftOrder inDraftOrder(List<LeagueDraftTeam> teams, List<LeagueDraftPick> picks) {
        Map<String, LeagueDraftTeam> remaining = new LinkedHashMap<>();
        teams.forEach(team -> remaining.put(team.teamKey(), team));
        List<LeagueDraftTeam> ordered = new ArrayList<>();
        for (LeagueDraftPick pick : picks) {
            LeagueDraftTeam team = pick.round() == 1 ? remaining.remove(pick.teamKey()) : null;
            if (team != null) {
                ordered.add(team);
            }
        }
        if (ordered.isEmpty()) {
            return new DraftOrder(teams, false);
        }
        boolean known = remaining.isEmpty();
        ordered.addAll(remaining.values());
        return new DraftOrder(ordered, known);
    }

    /** A draft result is an object, or an array of single-key objects; either way, one object. */
    private static JsonNode flatten(JsonNode node) {
        if (!node.isArray()) {
            return node;
        }
        ObjectNode merged =
                JsonNodeFactory.instance.objectNode();
        for (JsonNode part : node) {
            if (part.isObject()) {
                merged.setAll((ObjectNode) part);
            }
        }
        return merged;
    }

    static Integer playerId(String playerKey) {
        if (playerKey == null) {
            return null;
        }
        int marker = playerKey.lastIndexOf(".p.");
        if (marker < 0) {
            return null;
        }
        try {
            return Integer.valueOf(playerKey.substring(marker + 3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<StatCategory> parseStatCategories(JsonNode settings) {
        // Points leagues carry a per-stat modifier; index it by stat_id to merge in.
        Map<Integer, Double> modifiers = new HashMap<>();
        for (JsonNode wrapper : settings.path("stat_modifiers").path("stats")) {
            JsonNode stat = wrapper.path("stat");
            if (stat.hasNonNull("stat_id")) {
                modifiers.put(stat.path("stat_id").asInt(), doubleOrNull(stat, "value"));
            }
        }
        List<StatCategory> categories = new ArrayList<>();
        for (JsonNode wrapper : settings.path("stat_categories").path("stats")) {
            JsonNode stat = wrapper.path("stat");
            if (stat.path("stat_id").isMissingNode()) {
                continue;
            }
            int statId = stat.path("stat_id").asInt();
            categories.add(new StatCategory(
                    statId,
                    text(stat, "name"),
                    text(stat, "display_name"),
                    text(stat, "position_type"),
                    displayOnly(stat, "is_only_display_stat"),
                    modifiers.get(statId)));
        }
        return categories;
    }

    private static List<RosterSlot> parseRosterPositions(JsonNode settings) {
        List<RosterSlot> slots = new ArrayList<>();
        for (JsonNode wrapper : settings.path("roster_positions")) {
            JsonNode pos = wrapper.path("roster_position");
            if (pos.path("position").isMissingNode()) {
                continue;
            }
            slots.add(new RosterSlot(
                    text(pos, "position"),
                    pos.path("count").asInt(0),
                    text(pos, "position_type")));
        }
        return slots;
    }

    /** Children of a Yahoo numeric-keyed object ({@code {"0":…,"1":…,"count":N}}), in order. */
    private static List<JsonNode> numericChildren(JsonNode node) {
        List<JsonNode> children = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().chars().allMatch(Character::isDigit)) {
                children.add(field.getValue());
            }
        }
        return children;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A limit Yahoo's form cannot set (blank, not a number, nought or below) reads as none reported. */
    private static Integer positiveIntOrNull(String value) {
        Integer parsed = parseIntOrNull(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return Double.valueOf(node.get(field).asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean displayOnly(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return false;
        }
        JsonNode value = node.get(field);
        return value.asInt(0) == 1 || value.asBoolean();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
