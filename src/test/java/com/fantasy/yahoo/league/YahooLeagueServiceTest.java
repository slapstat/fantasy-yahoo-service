package com.fantasy.yahoo.league;

import com.fantasy.yahoo.league.dto.DraftStatus;
import com.fantasy.yahoo.league.dto.LeagueDraftResponse;
import com.fantasy.yahoo.league.dto.LeagueRosterPlayer;
import com.fantasy.yahoo.league.dto.LeagueRostersResponse;
import com.fantasy.yahoo.league.dto.LeagueSettingsResponse;
import com.fantasy.yahoo.league.dto.LeaguesResponse;
import com.fantasy.yahoo.oauth.YahooOAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YahooLeagueServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "user-1";

    @Mock
    private YahooOAuthService oauthService;
    @Mock
    private YahooFantasyClient client;

    private YahooLeagueService service() {
        return new YahooLeagueService(oauthService, client);
    }

    @Test
    void leagues_parsesYahooNestedShape() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getUserNhlLeagues("token")).thenReturn(json(
                "{\"fantasy_content\":{\"users\":{\"0\":{\"user\":[{\"guid\":\"X\"},{\"games\":{\"0\":"
                + "{\"game\":[{\"game_key\":\"453\",\"code\":\"nhl\"},{\"leagues\":{\"0\":{\"league\":"
                + "[{\"league_key\":\"453.l.123\",\"name\":\"My League\",\"season\":\"2025\","
                + "\"num_teams\":12,\"scoring_type\":\"head\"}]},\"count\":1}}]},\"count\":1}}]},"
                + "\"count\":1}}}"));

        LeaguesResponse response = service().leagues(USER);

        assertThat(response.leagues()).hasSize(1);
        assertThat(response.leagues().getFirst().leagueKey()).isEqualTo("453.l.123");
        assertThat(response.leagues().getFirst().name()).isEqualTo("My League");
        assertThat(response.leagues().getFirst().season()).isEqualTo(2025);
        assertThat(response.leagues().getFirst().numTeams()).isEqualTo(12);
        assertThat(response.leagues().getFirst().scoringType()).isEqualTo("head");
    }

    @Test
    void settings_parsesScoringAndRoster() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueSettings("token", "453.l.123")).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"453.l.123\",\"name\":\"My League\","
                + "\"scoring_type\":\"head\"},{\"settings\":[{\"scoring_type\":\"head\","
                + "\"stat_categories\":{\"stats\":["
                + "{\"stat\":{\"stat_id\":1,\"name\":\"Goals\",\"display_name\":\"G\",\"position_type\":\"P\"}},"
                + "{\"stat\":{\"stat_id\":2,\"name\":\"Assists\",\"display_name\":\"A\",\"position_type\":\"P\"}},"
                + "{\"stat\":{\"stat_id\":24,\"name\":\"Shots Against\",\"display_name\":\"SA\",\"position_type\":\"G\",\"is_only_display_stat\":\"1\"}}]},"
                + "\"stat_modifiers\":{\"stats\":["
                + "{\"stat\":{\"stat_id\":1,\"value\":\"3\"}},{\"stat\":{\"stat_id\":2,\"value\":\"2\"}}]},"
                + "\"roster_positions\":["
                + "{\"roster_position\":{\"position\":\"C\",\"position_type\":\"P\",\"count\":2}},"
                + "{\"roster_position\":{\"position\":\"BN\",\"count\":4}}],"
                + "\"max_weekly_adds\":\"4\"}]}]}}"));

        LeagueSettingsResponse settings = service().settings(USER, "453.l.123");

        assertThat(settings.leagueKey()).isEqualTo("453.l.123");
        assertThat(settings.name()).isEqualTo("My League");
        assertThat(settings.scoringType()).isEqualTo("head");
        assertThat(settings.statCategories()).hasSize(3);
        assertThat(settings.statCategories().getFirst().name()).isEqualTo("Goals");
        assertThat(settings.statCategories().getFirst().pointValue()).isEqualTo(3.0);
        assertThat(settings.statCategories().getFirst().displayOnly()).isFalse();
        assertThat(settings.statCategories().get(2).name()).isEqualTo("Shots Against");
        assertThat(settings.statCategories().get(2).displayOnly()).isTrue();
        assertThat(settings.rosterPositions()).hasSize(2);
        assertThat(settings.rosterPositions().getFirst().position()).isEqualTo("C");
        assertThat(settings.rosterPositions().getFirst().count()).isEqualTo(2);
        assertThat(settings.rosterPositions().get(1).position()).isEqualTo("BN");
        assertThat(settings.rosterPositions().get(1).count()).isEqualTo(4);
        assertThat(settings.maxWeeklyAdds()).isEqualTo(4);
    }

    @Test
    void settings_readsAWeeklyAddLimitSentAsANumber() throws Exception {
        assertThat(weeklyAddsFrom(",\"max_weekly_adds\":6")).isEqualTo(6);
    }

    @Test
    void settings_readsNoWeeklyAddLimitWhenYahooLeavesItOut() throws Exception {
        assertThat(weeklyAddsFrom("")).isNull();
    }

    @Test
    void settings_readsNoWeeklyAddLimitFromAValueYahooCannotSet() throws Exception {
        assertThat(weeklyAddsFrom(",\"max_weekly_adds\":\"0\"")).isNull();
        assertThat(weeklyAddsFrom(",\"max_weekly_adds\":\"-1\"")).isNull();
        assertThat(weeklyAddsFrom(",\"max_weekly_adds\":\"\"")).isNull();
        assertThat(weeklyAddsFrom(",\"max_weekly_adds\":\"four\"")).isNull();
        assertThat(weeklyAddsFrom(",\"max_weekly_adds\":null")).isNull();
    }

    private Integer weeklyAddsFrom(String field) throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueSettings("token", "453.l.123")).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"453.l.123\",\"name\":\"My League\"},"
                + "{\"settings\":[{\"scoring_type\":\"head\"" + field + "}]}]}}"));
        return service().settings(USER, "453.l.123").maxWeeklyAdds();
    }

    @Test
    void draft_ordersTeamsByFirstRoundAndParsesPicks() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueDraft("token", "465.l.9")).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\",\"draft_status\":\"inprogress\"},"
                + "{\"settings\":[{\"is_auction_draft\":\"0\"}]},"
                + "{\"draft_results\":{"
                + "\"0\":{\"draft_result\":{\"pick\":1,\"round\":1,\"team_key\":\"465.l.9.t.2\",\"player_key\":\"465.p.6743\"}},"
                + "\"1\":{\"draft_result\":{\"pick\":2,\"round\":1,\"team_key\":\"465.l.9.t.1\",\"player_key\":\"465.p.7109\"}},"
                + "\"2\":{\"draft_result\":{\"pick\":3,\"round\":2,\"team_key\":\"465.l.9.t.1\"}},"
                + "\"count\":3}},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"},{\"is_owned_by_current_login\":1}]]},"
                + "\"1\":{\"team\":[[{\"team_key\":\"465.l.9.t.2\"},{\"name\":\"Bravo\"}]]},"
                + "\"count\":2}}]}}"));

        LeagueDraftResponse draft = service().draft(USER, "465.l.9");

        assertThat(draft.leagueKey()).isEqualTo("465.l.9");
        assertThat(draft.status()).isEqualTo(DraftStatus.IN_PROGRESS);
        assertThat(draft.auction()).isFalse();
        assertThat(draft.teams()).extracting("teamKey").containsExactly("465.l.9.t.2", "465.l.9.t.1");
        assertThat(draft.orderKnown()).isTrue();
        assertThat(draft.teams().get(1).mine()).isTrue();
        assertThat(draft.picks()).hasSize(3);
        assertThat(draft.picks().getFirst().playerKey()).isEqualTo("465.p.6743");
        assertThat(draft.picks().getFirst().playerId()).isEqualTo(6743);
        assertThat(draft.picks().get(2).playerKey()).isNull();
        assertThat(draft.picks().get(2).playerId()).isNull();
    }

    @Test
    void draft_beforeAnyOrderKeepsYahooTeamOrderAndReadsAuction() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueDraft("token", "465.l.9")).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\",\"draft_status\":\"predraft\"},"
                + "{\"settings\":[{\"is_auction_draft\":\"1\"}]},"
                + "{\"draft_results\":[]},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"}]]},"
                + "\"1\":{\"team\":[[{\"team_key\":\"465.l.9.t.2\"},{\"name\":\"Bravo\"}]]},"
                + "\"count\":2}}]}}"));

        LeagueDraftResponse draft = service().draft(USER, "465.l.9");

        assertThat(draft.status()).isEqualTo(DraftStatus.PRE_DRAFT);
        assertThat(draft.auction()).isTrue();
        assertThat(draft.teams()).extracting("teamKey").containsExactly("465.l.9.t.1", "465.l.9.t.2");
        assertThat(draft.orderKnown()).isFalse();
        assertThat(draft.picks()).isEmpty();
    }

    @Test
    void draft_knowsTheOrderFromSlotsListedBeforeAnyPick() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueDraft("token", "465.l.9")).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\",\"draft_status\":\"predraft\"},"
                + "{\"settings\":[{\"is_auction_draft\":\"0\"}]},"
                + "{\"draft_results\":{"
                + "\"0\":{\"draft_result\":{\"pick\":1,\"round\":1,\"team_key\":\"465.l.9.t.3\"}},"
                + "\"1\":{\"draft_result\":{\"pick\":2,\"round\":1,\"team_key\":\"465.l.9.t.1\"}},"
                + "\"2\":{\"draft_result\":{\"pick\":3,\"round\":1,\"team_key\":\"465.l.9.t.2\"}},"
                + "\"3\":{\"draft_result\":{\"pick\":4,\"round\":2,\"team_key\":\"465.l.9.t.2\"}},"
                + "\"count\":4}},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"},{\"is_owned_by_current_login\":1}]]},"
                + "\"1\":{\"team\":[[{\"team_key\":\"465.l.9.t.2\"},{\"name\":\"Bravo\"}]]},"
                + "\"2\":{\"team\":[[{\"team_key\":\"465.l.9.t.3\"},{\"name\":\"Charlie\"}]]},"
                + "\"count\":3}}]}}"));

        LeagueDraftResponse draft = service().draft(USER, "465.l.9");

        assertThat(draft.teams()).extracting("teamKey")
                .containsExactly("465.l.9.t.3", "465.l.9.t.1", "465.l.9.t.2");
        assertThat(draft.orderKnown()).isTrue();
        assertThat(draft.picks()).extracting("playerId").containsOnlyNulls();
    }

    /**
     * A team that traded its first-round pick away holds no first-round slot, so it can only be put
     * where Yahoo listed it, and the other teams' places are counted past a team picking twice.
     */
    @Test
    void draft_doesNotClaimTheOrderWhenATeamHasNoFirstRoundSlot() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueDraft("token", "465.l.9")).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\",\"draft_status\":\"predraft\"},"
                + "{\"settings\":[{\"is_auction_draft\":\"0\"}]},"
                + "{\"draft_results\":{"
                + "\"0\":{\"draft_result\":{\"pick\":1,\"round\":1,\"team_key\":\"465.l.9.t.3\"}},"
                + "\"1\":{\"draft_result\":{\"pick\":2,\"round\":1,\"team_key\":\"465.l.9.t.3\"}},"
                + "\"2\":{\"draft_result\":{\"pick\":3,\"round\":1,\"team_key\":\"465.l.9.t.1\"}},"
                + "\"count\":3}},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"},{\"is_owned_by_current_login\":1}]]},"
                + "\"1\":{\"team\":[[{\"team_key\":\"465.l.9.t.2\"},{\"name\":\"Bravo\"}]]},"
                + "\"2\":{\"team\":[[{\"team_key\":\"465.l.9.t.3\"},{\"name\":\"Charlie\"}]]},"
                + "\"count\":3}}]}}"));

        LeagueDraftResponse draft = service().draft(USER, "465.l.9");

        assertThat(draft.teams()).extracting("teamKey")
                .containsExactly("465.l.9.t.3", "465.l.9.t.1", "465.l.9.t.2");
        assertThat(draft.orderKnown()).isFalse();
    }

    @Test
    void rosters_listsEachTeamsCurrentPlayersWithTheirSlots() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueRosters("token", "465.l.9", null)).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\",\"name\":\"Test League\"},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"team_id\":\"1\"},{\"name\":\"Alpha\"},[],"
                + "{\"is_owned_by_current_login\":1}],"
                + "{\"roster\":{\"coverage_type\":\"date\",\"date\":\"2026-10-07\",\"is_editable\":1,"
                + "\"0\":{\"players\":{"
                + "\"0\":{\"player\":[[{\"player_key\":\"465.p.6743\"},{\"player_id\":\"6743\"},"
                + "{\"name\":{\"full\":\"Player One\"}},{\"status\":\"DTD\"},{\"editorial_team_abbr\":\"EDM\"},"
                + "{\"uniform_number\":\"97\"},{\"position_type\":\"P\"},"
                + "{\"eligible_positions\":[{\"position\":\"C\"},{\"position\":\"LW\"},{\"position\":\"Util\"}]}],"
                + "{\"selected_position\":[{\"coverage_type\":\"date\",\"date\":\"2026-10-07\"},{\"position\":\"C\"}]},"
                + "{\"is_editable\":1}]},"
                + "\"1\":{\"player\":[[{\"player_key\":\"465.p.7109\"},{\"player_id\":\"7109\"},"
                + "{\"position_type\":\"G\"},{\"uniform_number\":\"\"}],"
                + "{\"selected_position\":[{\"coverage_type\":\"date\"},{\"position\":\"IR+\"}]}]},"
                + "\"count\":2}},\"outs_allowed\":0}}]},"
                + "\"1\":{\"team\":[[{\"team_key\":\"465.l.9.t.2\"},{\"team_id\":\"2\"},{\"name\":\"Bravo\"}],"
                + "{\"roster\":{\"coverage_type\":\"date\",\"0\":{\"players\":{"
                + "\"0\":{\"player\":[[{\"player_key\":\"465.p.5000\"}],"
                + "{\"selected_position\":[{\"position\":\"BN\"}]}]},"
                + "\"count\":1}}}}]},"
                + "\"count\":2}}]}}"));

        LeagueRostersResponse rosters = service().rosters(USER, "465.l.9", null);

        assertThat(rosters.leagueKey()).isEqualTo("465.l.9");
        assertThat(rosters.teams()).extracting("teamKey").containsExactly("465.l.9.t.1", "465.l.9.t.2");
        assertThat(rosters.teams().getFirst().name()).isEqualTo("Alpha");
        assertThat(rosters.teams().getFirst().mine()).isTrue();
        assertThat(rosters.teams().get(1).mine()).isFalse();
        assertThat(rosters.teams().getFirst().players()).extracting("playerId").containsExactly(6743, 7109);
        assertThat(rosters.teams().getFirst().players()).extracting("playerKey")
                .containsExactly("465.p.6743", "465.p.7109");
        assertThat(rosters.teams().getFirst().players()).extracting("selectedPosition").containsExactly("C", "IR+");
        assertThat(rosters.teams().get(1).players()).extracting("playerId").containsExactly(5000);
        assertThat(rosters.teams().get(1).players().getFirst().selectedPosition()).isEqualTo("BN");

        LeagueRosterPlayer one = rosters.teams().getFirst().players().getFirst();
        assertThat(one.fullName()).isEqualTo("Player One");
        assertThat(one.teamAbbrev()).isEqualTo("EDM");
        assertThat(one.uniformNumber()).isEqualTo(97);
        assertThat(one.goalie()).isFalse();
        assertThat(one.eligiblePositions()).containsExactly("C", "LW", "Util");
        assertThat(one.status()).isEqualTo("DTD");
        LeagueRosterPlayer two = rosters.teams().getFirst().players().get(1);
        assertThat(two.goalie()).isTrue();
        assertThat(two.uniformNumber()).isNull();
        assertThat(two.eligiblePositions()).isEmpty();
        assertThat(two.status()).isNull();
    }

    @Test
    void rosters_beforeTheDraftListsTeamsWithEmptyRosters() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueRosters("token", "465.l.9", null)).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\"},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"}],"
                + "{\"roster\":{\"coverage_type\":\"date\",\"0\":{\"players\":[]}}}]},"
                + "\"1\":{\"team\":[[{\"team_key\":\"465.l.9.t.2\"},{\"name\":\"Bravo\"}],"
                + "{\"roster\":{\"coverage_type\":\"date\"}}]},"
                + "\"count\":2}}]}}"));

        LeagueRostersResponse rosters = service().rosters(USER, "465.l.9", null);

        assertThat(rosters.teams()).hasSize(2);
        assertThat(rosters.teams()).allSatisfy(team -> assertThat(team.players()).isEmpty());
    }

    @Test
    void rosters_leavesOutAPlayerWhoseKeyCarriesNoIdAndATeamWithoutAKey() throws Exception {
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueRosters("token", "465.l.9", null)).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\"},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"}],"
                + "{\"roster\":{\"0\":{\"players\":{"
                + "\"0\":{\"player\":[[{\"player_key\":\"465.p.x\"}]]},"
                + "\"1\":{\"player\":[[{\"name\":{\"full\":\"No Key\"}}]]},"
                + "\"2\":{\"player\":[[{\"player_key\":\"465.p.42\"}]]},"
                + "\"count\":3}}}}]},"
                + "\"1\":{\"team\":[[{\"name\":\"Keyless\"}],{\"roster\":{}}]},"
                + "\"count\":2}}]}}"));

        LeagueRostersResponse rosters = service().rosters(USER, "465.l.9", null);

        assertThat(rosters.teams()).extracting("teamKey").containsExactly("465.l.9.t.1");
        assertThat(rosters.teams().getFirst().players()).extracting("playerId").containsExactly(42);
        assertThat(rosters.teams().getFirst().players().getFirst().selectedPosition()).isNull();
    }

    @Test
    void rosters_forADate_readsTheLineupYahooHasForThatDay() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 14);
        when(oauthService.validAccessToken(USER)).thenReturn("token");
        when(client.getLeagueRosters("token", "465.l.9", day)).thenReturn(json(
                "{\"fantasy_content\":{\"league\":[{\"league_key\":\"465.l.9\"},"
                + "{\"teams\":{"
                + "\"0\":{\"team\":[[{\"team_key\":\"465.l.9.t.1\"},{\"name\":\"Alpha\"}],"
                + "{\"roster\":{\"coverage_type\":\"date\",\"date\":\"2026-10-14\",\"0\":{\"players\":{"
                + "\"0\":{\"player\":[[{\"player_key\":\"465.p.6743\"}],"
                + "{\"selected_position\":[{\"coverage_type\":\"date\",\"date\":\"2026-10-14\"},"
                + "{\"position\":\"BN\"}]}]},"
                + "\"count\":1}}}}]},"
                + "\"count\":1}}]}}"));

        LeagueRostersResponse rosters = service().rosters(USER, "465.l.9", day);

        verify(client).getLeagueRosters("token", "465.l.9", day);
        assertThat(rosters.teams().getFirst().players().getFirst().selectedPosition()).isEqualTo("BN");
    }

    @Test
    void draftStatus_mapsYahooValues() {
        assertThat(YahooLeagueService.draftStatus("predraft")).isEqualTo(DraftStatus.PRE_DRAFT);
        assertThat(YahooLeagueService.draftStatus("postdraft")).isEqualTo(DraftStatus.FINISHED);
        assertThat(YahooLeagueService.draftStatus("inprogress")).isEqualTo(DraftStatus.IN_PROGRESS);
        assertThat(YahooLeagueService.draftStatus(null)).isEqualTo(DraftStatus.UNKNOWN);
    }

    @Test
    void playerId_readsTheNumberAfterThePlayerMarker() {
        assertThat(YahooLeagueService.playerId("465.p.6743")).isEqualTo(6743);
        assertThat(YahooLeagueService.playerId("nhl.p.12")).isEqualTo(12);
        assertThat(YahooLeagueService.playerId("465.t.1")).isNull();
        assertThat(YahooLeagueService.playerId("465.p.x")).isNull();
        assertThat(YahooLeagueService.playerId(null)).isNull();
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }
}
