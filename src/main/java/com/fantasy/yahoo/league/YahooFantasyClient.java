package com.fantasy.yahoo.league;

import com.fantasy.yahoo.exception.YahooAccessDeniedException;
import com.fantasy.yahoo.exception.YahooUpstreamException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;

import java.net.ConnectException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin wrapper over the Yahoo Fantasy Sports API. Returns raw Jackson {@link JsonNode}
 * trees because Yahoo's JSON is deeply nested with numeric-keyed objects mixed into
 * arrays — the {@link YahooLeagueService} parses defensively rather than binding to it.
 *
 * The body is fetched as a String and parsed with our own (Jackson 2) ObjectMapper:
 * Spring Boot 4's default converter is Jackson 3, which can't build a Jackson 2 JsonNode.
 */
@Component
public class YahooFantasyClient {

    /** The positions a league's player collection can be filtered by. */
    public static final String POSITION_PATTERN = "^(C|LW|RW|D|G)$";

    private static final Logger log = LoggerFactory.getLogger(YahooFantasyClient.class);

    private static final int CONNECT_ATTEMPTS = 2;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long connectRetryBackoffMillis;

    public YahooFantasyClient(RestClient yahooApiRestClient,
                              @Value("${yahoo.fantasy.connect-retry-backoff-ms:200}")
                              long connectRetryBackoffMillis) {
        this.restClient = yahooApiRestClient;
        this.connectRetryBackoffMillis = connectRetryBackoffMillis;
    }

    private static final String USER_LEAGUES_PATH =
            "/users;use_login=1/games;game_keys=nhl/leagues?format=json";

    /** The NHL leagues the authenticated user belongs to. */
    public JsonNode getUserNhlLeagues(String accessToken) {
        return get(accessToken, USER_LEAGUES_PATH);
    }

    /** A league's settings (scoring categories, roster positions, modifiers). */
    public JsonNode getLeagueSettings(String accessToken, String leagueKey) {
        // leagueKey is caller-supplied — pass it as a URI-template variable so RestClient
        // URL-encodes it into a single path segment (a stray '/', '?' etc. can't inject path or
        // query). Format-agnostic: no assumption about Yahoo's key shape.
        return get(accessToken, "/league/{leagueKey}/settings?format=json", leagueKey);
    }

    /**
     * One page of the players a league has available: free agents and players on waivers, best
     * first by Yahoo's own actual rank, with the ownership subresource that tells the two apart.
     *
     * <p>{@code status=A} is Yahoo's "available", which is the union of the two. Asking for free
     * agents alone would hide the player a rival dropped an hour ago, who is the one a streamer is
     * most often looking for.
     *
     * <p>A {@code position} narrows the list to the players eligible there, so a caller can read
     * a position's whole wire instead of the few that make a mixed top list. It is spliced into
     * the path, so only the allow-listed codes get through.
     *
     * <p>{@code waiversOnly} asks for {@code status=W} instead: the players on waivers alone. That
     * list is short, so a caller can read all of it and know the waiver status of every
     * available player, not only of the ones that rank high enough to make a capped list.
     */
    public JsonNode getAvailablePlayers(
            String accessToken, String leagueKey, String position, boolean waiversOnly, int start, int count) {
        return get(accessToken,
                "/league/{leagueKey}/players;status=" + (waiversOnly ? "W" : "A") + positionFilter(position)
                        + ";sort=AR;start="
                        + start + ";count=" + count + ";out=ownership?format=json",
                leagueKey);
    }

    private static String positionFilter(String position) {
        if (position == null) {
            return "";
        }
        if (!position.matches(POSITION_PATTERN)) {
            throw new IllegalArgumentException("Not a player position: " + position);
        }
        return ";position=" + position;
    }

    /**
     * A league's draft in one call: its metadata (which carries {@code draft_status}), settings,
     * draft results and teams.
     */
    public JsonNode getLeagueDraft(String accessToken, String leagueKey) {
        return get(accessToken, "/league/{leagueKey};out=settings,draftresults,teams?format=json", leagueKey);
    }

    /**
     * A league's teams, each with the players on its roster today. Yahoo's roster is the current
     * one, so a player traded, dropped or picked up since the draft sits where he is now.
     *
     * <p>With a {@code date}, each roster is the lineup as set for that day: Yahoo keeps one per
     * day, so a player's {@code selected_position} is his slot on that date, and a future day shows
     * what the manager has set for it so far.
     */
    public JsonNode getLeagueRosters(String accessToken, String leagueKey, LocalDate date) {
        if (date == null) {
            return get(accessToken, "/league/{leagueKey}/teams/roster?format=json", leagueKey);
        }
        return get(accessToken, "/league/{leagueKey}/teams/roster;date={date}?format=json",
                leagueKey, date.toString());
    }

    /**
     * One page (25) of a game's player collection with each player's season stat line,
     * starting at the given offset. When {@code season} is blank Yahoo uses the current season.
     */
    public JsonNode getGamePlayers(String accessToken, String gameKey, int start, String season) {
        return get(accessToken, gamePlayersPath(gameKey, start, season));
    }

    /**
     * One page of a *league's* player collection. Same shape as the game's, but reached through a
     * league the account belongs to — which is the only route the Fantasy API is documented to
     * offer, and the one that survives when the game-wide collection is refused.
     */
    public JsonNode getLeaguePlayers(String accessToken, String leagueKey, int start, String season) {
        String stats = (season == null || season.isBlank())
                ? "/stats;type=season"
                : "/stats;type=season;season=" + season;
        // The key is caller-supplied, so it goes in as a URI-template variable and is encoded
        // into a single path segment.
        return get(accessToken,
                "/league/{leagueKey}/players;start=" + start + ";count=25" + stats + "?format=json",
                leagueKey);
    }

    /**
     * One page of a league's player collection with each player's percent rostered. The number is
     * the share of all Yahoo leagues that roster the player, not of this league, so the league it
     * is read through does not change it.
     */
    public JsonNode getLeaguePlayersPercentOwned(String accessToken, String leagueKey, int start) {
        return get(accessToken,
                "/league/{leagueKey}/players;start=" + start + ";count=25/percent_owned?format=json",
                leagueKey);
    }

    /**
     * The outcome of one call, with the failure as data rather than an exception.
     *
     * @param status Yahoo's HTTP status, or null when no response arrived at all
     * @param body the response body, or Yahoo's error body on a non-2xx
     * @param error what went wrong, or null on success
     */
    public record Attempt(String path, Integer status, String body, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Fetches a page of a game's players and reports what happened instead of throwing.
     *
     * <p>Every other call here turns a Yahoo failure into a {@link YahooUpstreamException}, which
     * is right when the caller needs the data — but useless when the failure *is* what you came
     * to look at. A 403 with Yahoo's own wording, against a game key and season you chose, is the
     * only thing that distinguishes "we are not allowed" from "that season is not there" from
     * "our token is dead". So this one hands the status back.
     */
    public Attempt attemptGamePlayers(String accessToken, String gameKey, String season) {
        String path = gamePlayersPath(gameKey, 0, season);
        return attempt(accessToken, path, path);
    }

    /**
     * The same question asked of a league rather than a whole game.
     *
     * <p>Worth having both: the granted Fantasy Sports scope talks about the user's own teams and
     * leagues, while a game's player collection belongs to nobody in particular. If one is refused
     * and the other is not, that difference is the answer.
     */
    public Attempt attemptLeaguePlayers(String accessToken, String leagueKey) {
        // The key is caller-supplied, so it goes in as a URI-template variable and gets encoded
        // into a single path segment — a stray '/' or '?' cannot reshape the request.
        return attempt(accessToken, leaguePlayersPath("{leagueKey}"),
                leaguePlayersPath(leagueKey), leagueKey);
    }

    /**
     * Whether the account can list its own leagues at all — the most basic thing the granted
     * scope covers, and so the one that separates "this call is not allowed" from "nothing is".
     */
    public Attempt attemptUserLeagues(String accessToken) {
        return attempt(accessToken, USER_LEAGUES_PATH, USER_LEAGUES_PATH);
    }

    /** The league resources {@link #attemptLeagueResource} may ask for, by the name a caller uses. */
    public static final Map<String, String> LEAGUE_RESOURCES = Map.of(
            "settings", "/settings",
            "teams", "/teams",
            "draftresults", "/draftresults",
            "draft", ";out=settings,draftresults,teams",
            "rosters", "/teams/roster");

    /**
     * One of a league's resources, raw. For diagnosing what Yahoo actually sends before code is
     * written against it: a field that is absent from the answer cannot be parsed into existence.
     */
    public Attempt attemptLeagueResource(String accessToken, String leagueKey, String resource) {
        String suffix = LEAGUE_RESOURCES.get(resource);
        if (suffix == null) {
            throw new IllegalArgumentException("Unknown league resource");
        }
        return attempt(accessToken, "/league/{leagueKey}" + suffix + "?format=json",
                "/league/" + leagueKey + suffix, leagueKey);
    }

    private Attempt attempt(String accessToken, String uriTemplate, String display, Object... vars) {
        try {
            String body = restClient.get()
                    .uri(uriTemplate, vars)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return new Attempt(display, 200, body, null);
        } catch (RestClientResponseException e) {
            return new Attempt(display, e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getStatusText());
        } catch (RestClientException e) {
            return new Attempt(display, null, null, e.getMessage());
        }
    }

    /**
     * The sentence in a Yahoo error body's {@code error.description} — "This application is not
     * authorized to perform this action" and the like — on one line, or null when the body is
     * shaped differently.
     */
    public static String errorDescription(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode description = new ObjectMapper().readTree(body).path("error").path("description");
            return description.isTextual() && !description.asText().isBlank()
                    ? description.asText().replace("\r", " ").replace("\n", " ")
                    : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String leaguePlayersPath(String leagueKey) {
        return "/league/" + leagueKey + "/players;start=0;count=25/stats;type=season?format=json";
    }

    private static String gamePlayersPath(String gameKey, int start, String season) {
        String stats = (season == null || season.isBlank())
                ? "/stats;type=season"
                : "/stats;type=season;season=" + season;
        return "/game/" + gameKey + "/players;start=" + start + ";count=25" + stats + "?format=json";
    }

    private JsonNode get(String accessToken, String uriTemplate, Object... uriVariables) {
        String body;
        try {
            body = fetchRetryingConnectFailures(accessToken, uriTemplate, uriVariables);
        } catch (HttpClientErrorException.Forbidden e) {
            String description = errorDescription(e.getResponseBodyAsString());
            throw new YahooAccessDeniedException(description != null
                    ? "Yahoo refused the request: " + description
                    : "Yahoo refused the request", e);
        } catch (RestClientException e) {
            throw new YahooUpstreamException(
                    "Yahoo Fantasy API call failed for " + uriTemplate + ": " + e.getMessage(), e);
        }
        if (body == null || body.isBlank()) {
            throw new YahooUpstreamException("Yahoo Fantasy API returned an empty body for " + uriTemplate);
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new YahooUpstreamException("Yahoo Fantasy API returned unparseable JSON for " + uriTemplate, e);
        }
    }

    /**
     * A call whose connection failed was never sent, so asking again cannot do anything twice;
     * that one failure is retried. A read timeout or an error status is not: the request may have
     * reached Yahoo, and a 403 has to fail at once for the off-season switch.
     */
    private String fetchRetryingConnectFailures(String accessToken, String uriTemplate, Object... uriVariables) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.get()
                        .uri(uriTemplate, uriVariables)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .body(String.class);
            } catch (ResourceAccessException e) {
                if (attempt >= CONNECT_ATTEMPTS || !isConnectFailure(e)) {
                    throw e;
                }
                // Only the failure's type: its message quotes the request URL, and the last
                // attempt's failure carries the whole trace up to the caller anyway.
                log.warn("Yahoo Fantasy API call could not connect on attempt {} of {} ({}); retrying",
                        attempt, CONNECT_ATTEMPTS, rootCauseType(e));
                backOff();
            }
        }
    }

    private static boolean isConnectFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static String rootCauseType(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private void backOff() {
        if (connectRetryBackoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(connectRetryBackoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YahooUpstreamException("Interrupted while retrying a Yahoo Fantasy API call", e);
        }
    }
}
