package com.fantasy.yahoo.league;

import com.fantasy.yahoo.exception.YahooAccessDeniedException;
import com.fantasy.yahoo.exception.YahooUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFantasyClientTest {

    private MockRestServiceServer server;
    private YahooFantasyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://fantasy.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new YahooFantasyClient(builder.build(), 0);
    }

    @Test
    void getLeagueSettings_putsTheLeagueKeyInThePathWithBearerToken() {
        server.expect(requestTo(containsString("/league/453.l.123/settings?format=json")))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getLeagueSettings("tok", "453.l.123");

        server.verify();
    }

    @Test
    void getAvailablePlayers_asksForAvailablePlayersInRankOrderWithOwnership() {
        server.expect(requestTo(containsString(
                        "/league/453.l.123/players;status=A;sort=AR;start=25;count=10;out=ownership?format=json")))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getAvailablePlayers("tok", "453.l.123", null, false, 25, 10);

        server.verify();
    }

    @Test
    void getAvailablePlayers_filtersByPositionWhenOneIsGiven() {
        server.expect(requestTo(containsString(
                        "/league/453.l.123/players;status=A;position=G;sort=AR;start=0;count=25;out=ownership")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getAvailablePlayers("tok", "453.l.123", "G", false, 0, 25);

        server.verify();
    }

    @Test
    void getAvailablePlayers_asksForWaiversAloneWhenWaiversOnly() {
        server.expect(requestTo(containsString(
                        "/league/453.l.123/players;status=W;sort=AR;start=0;count=25;out=ownership")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getAvailablePlayers("tok", "453.l.123", null, true, 0, 25);

        server.verify();
    }

    @Test
    void getAvailablePlayers_refusesAPositionOutsideTheAllowList() {
        assertThatThrownBy(() -> client.getAvailablePlayers("tok", "453.l.123", "G;status=T", false, 0, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getLeagueSettings_urlEncodesTheLeagueKeySoItCannotInjectThePath() {
        // A '/' in the key must be percent-encoded (%2F) so it stays inside one path segment and
        // can't break out into a different Yahoo API path. Encoding is format-agnostic — no
        // assumption about Yahoo's key shape, so a future Yahoo format change won't 400 real keys.
        server.expect(requestTo(containsString("/league/453.l.1%2F..%2Fsecret/settings")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getLeagueSettings("tok", "453.l.1/../secret");

        server.verify();
    }

    @Test
    void getLeagueDraft_urlEncodesTheLeagueKey() {
        server.expect(requestTo(containsString("/league/453.l.1%2F..%2Fsecret;out=settings,draftresults,teams")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getLeagueDraft("tok", "453.l.1/../secret");

        server.verify();
    }

    @Test
    void getLeagueRosters_asksForEveryTeamsRosterWithTheLeagueKeyEncoded() {
        server.expect(requestTo(containsString("/league/453.l.1%2F..%2Fsecret/teams/roster?format=json")))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getLeagueRosters("tok", "453.l.1/../secret", null);

        server.verify();
    }

    @Test
    void getLeagueRosters_withADate_asksForEachRosterAsSetForThatDay() {
        server.expect(requestTo(containsString(
                        "/league/453.l.1%2F..%2Fsecret/teams/roster;date=2026-10-14?format=json")))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getLeagueRosters("tok", "453.l.1/../secret", LocalDate.of(2026, 10, 14));

        server.verify();
    }

    @Test
    void aYahooErrorStatus_isAnUpstreamFailure() {
        server.expect(requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooUpstreamException.class);
    }

    /** What Yahoo answered, verbatim, every call from 2026-08-26 until it restored the app. */
    @Test
    void aForbidden_isARefusalCarryingYahoosOwnWording() {
        server.expect(requestTo(containsString("/users;use_login=1/games;game_keys=nhl/leagues")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error": {"xml:lang": "en-us",
                          "description": "This application is not authorized to perform this action.",
                          "detail": ""}}"""));

        assertThatThrownBy(() -> client.getUserNhlLeagues("tok"))
                .isInstanceOf(YahooAccessDeniedException.class)
                .hasMessage("Yahoo refused the request: "
                        + "This application is not authorized to perform this action.");
    }

    @Test
    void aForbiddenWithoutYahoosErrorShape_isStillARefusal() {
        server.expect(requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("<html>nope</html>"));

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooAccessDeniedException.class)
                .hasMessage("Yahoo refused the request");
    }

    @Test
    void anEmptyBody_isAnUpstreamFailure() {
        server.expect(requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooUpstreamException.class);
    }

    private static ConnectException connectFailure() {
        ConnectException e = new ConnectException();
        e.initCause(new ClosedChannelException());
        return e;
    }

    @Test
    void aConnectFailure_isRetriedOnceAndTheSecondAnswerIsReturned() {
        server.expect(once(), requestTo(containsString("/users;use_login=1/games;game_keys=nhl/leagues")))
                .andRespond(request -> {
                    throw connectFailure();
                });
        server.expect(once(), requestTo(containsString("/users;use_login=1/games;game_keys=nhl/leagues")))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"fantasy_content\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.getUserNhlLeagues("tok").has("fantasy_content")).isTrue();

        server.verify();
    }

    @Test
    void aConnectFailureTwice_isAnUpstreamFailure() {
        server.expect(once(), requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(request -> {
                    throw connectFailure();
                });
        server.expect(once(), requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(request -> {
                    throw connectFailure();
                });

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooUpstreamException.class)
                .hasRootCauseInstanceOf(ClosedChannelException.class);

        server.verify();
    }

    @Test
    void aReadTimeout_isNotRetried() {
        server.expect(once(), requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooUpstreamException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        server.verify();
    }

    @Test
    void aConnectTimeout_isNotRetried() {
        server.expect(once(), requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(request -> {
                    // As the JDK builds it: the timeout, caused by a ConnectException.
                    HttpConnectTimeoutException e = new HttpConnectTimeoutException("HTTP connect timed out");
                    e.initCause(new ConnectException("HTTP connect timed out"));
                    throw e;
                });

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooUpstreamException.class)
                .hasRootCauseInstanceOf(ConnectException.class);

        server.verify();
    }

    @Test
    void aServerError_isNotRetried() {
        server.expect(once(), requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooUpstreamException.class);

        server.verify();
    }

    @Test
    void aForbidden_isNotRetried() {
        server.expect(once(), requestTo(containsString("/league/453.l.123/settings")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getLeagueSettings("tok", "453.l.123"))
                .isInstanceOf(YahooAccessDeniedException.class);

        server.verify();
    }
}
