package pw.chew.mlb.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.json.JSONArray;
import org.json.JSONObject;
import pw.chew.chewbotcca.util.RestClient;
import pw.chew.mlb.objects.MLBAPIUtil;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class PlanGameCommand extends SlashCommand {
    public PlanGameCommand() {
        this.name = "plangame";
        this.help = "Plans a game to be played. Makes a thread in text channels or a post in forum channels.";

        this.options = Arrays.asList(
            new OptionData(OptionType.STRING, "team", "The team to plan for", true)
                .setAutoComplete(true),
            new OptionData(OptionType.CHANNEL, "channel", "The channel to plan for", true)
                .setChannelTypes(ChannelType.TEXT, ChannelType.FORUM),
            new OptionData(OptionType.STRING, "date", "The date of the game. Select one from the list!", true)
                .setAutoComplete(true),
            new OptionData(OptionType.STRING, "sport", "The sport to plan a game for, Majors by default. Type text in team to find suggestions.", false)
                .setAutoComplete(true),
            new OptionData(OptionType.BOOLEAN, "thread", "Whether to make a thread or not. Defaults to true, required true for forums.", false),
            new OptionData(OptionType.BOOLEAN, "event", "Whether to additionally create an event with all the information. Defaults to false.", false)
        );
    }

    public static String generateGameBlurb(String gamePk) {
        GameBlurb blurb = new GameBlurb(gamePk);
        return blurb.blurb();
    }

    private static String generateGameBlurb(String gamePk, JSONObject game) {
        // Format "2023-02-26T20:05:00Z" to OffsetDateTime
        TemporalAccessor accessor = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(game.getString("gameDate"));

        // Teams
        JSONObject home = game.getJSONObject("teams").getJSONObject("home");
        JSONObject away = game.getJSONObject("teams").getJSONObject("away");

        JSONObject homeRecord = home.getJSONObject("leagueRecord");
        JSONObject awayRecord = away.getJSONObject("leagueRecord");

        String homeName = home.getJSONObject("team").getString("teamName");
        String awayName = away.getJSONObject("team").getString("teamName");

        // Get probable pitchers
        JSONObject fallback = new JSONObject().put("fullName", "TBD");
        String homePitcher = home.optJSONObject("probablePitcher", fallback).getString("fullName");
        String awayPitcher = away.optJSONObject("probablePitcher", fallback).getString("fullName");

        // Handle broadcast stuff
        List<String> tv = new ArrayList<>();
        List<String> radio = new ArrayList<>();
        JSONArray broadcasts = game.optJSONArray("broadcasts");
        if (broadcasts == null) broadcasts = new JSONArray();
        for (Object broadcastObj : broadcasts) {
            JSONObject broadcast = (JSONObject) broadcastObj;
            String team = broadcast.getString("homeAway").equals("away") ? awayName : homeName;
            switch (broadcast.getString("type")) {
                case "TV" -> {
                    if (broadcast.getString("name").contains("Bally Sports")) {
                        // use call sign
                        tv.add("%s - %s".formatted(team, broadcast.getString("callSign")));
                    } else {
                        tv.add("%s - %s".formatted(team, broadcast.getString("name")));
                    }
                }
                case "FM", "AM" -> radio.add("%s - %s".formatted(team, broadcast.getString("name")));
            }
        }

        // Go through radio and see if the teamName is twice, if so, merge them
        cleanDuplicates(tv);
        cleanDuplicates(radio);

        // if tv or radio are empty, put "No TV/Radio Broadcasts"
        if (tv.isEmpty()) tv.add("No TV Broadcasts");
        if (radio.isEmpty()) radio.add("No Radio Broadcasts");

        return """
            **%s** @ **%s**
            **Game Time**: %s
            
            **Probable Pitchers**
            %s: %s
            %s: %s
            
            **Records**
            %s: %s - %s
            %s: %s - %s
            
            :tv:
            %s
            :radio:
            %s
            
            Game Link: https://mlb.chew.pw/game/%s
            """.formatted(
                awayName, homeName, // teams
            TimeFormat.DATE_TIME_LONG.format(accessor), // game time
            awayName, awayPitcher, // away pitcher
            homeName, homePitcher, // home pitcher
            awayName, awayRecord.getInt("wins"), awayRecord.getInt("losses"), // away record
            homeName, homeRecord.getInt("wins"), homeRecord.getInt("losses"), // home record
            String.join("\n", tv), String.join("\n", radio), // tv and radio broadcasts
            gamePk // game pk
        );
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        OptionMapping channelMapping = event.getOption("channel");
        if (channelMapping == null) {
            event.reply("You must specify a channel to plan for!").setEphemeral(true).queue();
            return;
        }
        GuildChannelUnion channel = channelMapping.getAsChannel();

        String gamePk = event.optString("date", "1");
        GameBlurb blurb = new GameBlurb(gamePk);

        boolean makeThread = event.optBoolean("thread", true);
        boolean makeEvent = event.optBoolean("event", false);

        if (makeEvent) {
            String name = blurb.name();

            OffsetDateTime start = blurb.time();
            if (start.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                start = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(15);
            }

            try {
                channel.getGuild().createScheduledEvent(name, "Ballpark", start, start.plus(4, ChronoUnit.HOURS))
                    .setDescription(blurb.blurb()).queue();
            } catch (InsufficientPermissionException e) {
                event.reply("I don't have permission to create events!").setEphemeral(true).queue();
                    return;
            }
        }

        switch (channel.getType()) {
            case TEXT -> {
                if (!makeThread) {
                    channel.asTextChannel().sendMessage(blurb.blurb()).setActionRow(buildButtons(gamePk)).queue(message -> {
                        event.reply("Planned game! " + message.getJumpUrl()).setEphemeral(true).queue();
                    });
                    return;
                }

                channel.asTextChannel().createThreadChannel(blurb.name()).queue(threadChannel -> {
                    threadChannel.sendMessage(blurb.blurb()).setActionRow(buildButtons(gamePk)).queue(msg -> {
                        try {
                            msg.pin().queue();
                        } catch (InsufficientPermissionException ignored) {
                        }
                        event.reply("Planned game! " + threadChannel.getAsMention()).setEphemeral(true).queue();
                    });
                });
            }
            case FORUM ->
                channel.asForumChannel().createForumPost(blurb.name(), MessageCreateData.fromContent(blurb.blurb())).setActionRow(buildButtons(gamePk)).queue(forumPost -> {
                    try {
                        forumPost.getMessage().pin().queue();
                    } catch (InsufficientPermissionException ignored) {
                    }
                    event.reply("Planned game! " + forumPost.getThreadChannel().getAsMention()).setEphemeral(true).queue();
                });
        }
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        switch (event.getFocusedOption().getName()) {
            case "team" -> {
                // get current value of sport
                String sport = event.getOption("sport", "1", OptionMapping::getAsString);
                String input = event.getFocusedOption().getValue();

                if (input.equals("")) {
                    event.replyChoices(MLBAPIUtil.getTeams(sport).asChoices()).queue();
                } else {
                    event.replyChoices(MLBAPIUtil.getTeams(sport).potentialChoices(input)).queue();
                }

                return;
            }
            case "sport" -> {
                event.replyChoices(MLBAPIUtil.getSports().asChoices()).queue();
                return;
            }
            case "date" -> {
                int teamId = event.getOption("team", -1, OptionMapping::getAsInt);
                String sport = event.getOption("sport", "1", OptionMapping::getAsString);

                if (teamId == -1) {
                    event.replyChoices(new Command.Choice("Please select a team first!", -1)).queue();
                    return;
                }

                JSONArray games = new JSONObject(RestClient.get("https://statsapi.mlb.com/api/v1/schedule?lang=en&sportId=%S&season=2023&teamId=%S&fields=dates,date,games,gamePk,teams,away,team,teamName,id&hydrate=team".formatted(sport, teamId)))
                    .getJSONArray("dates");

                List<Command.Choice> choices = new ArrayList<>();
                for (int i = 0; i < games.length(); i++) {
                    // Formatted as YYYY-MM-DD
                    String date = games.getJSONObject(i).getString("date");

                    Calendar c1 = Calendar.getInstance(); // today
                    c1.add(Calendar.DAY_OF_YEAR, -1); // yesterday

                    Calendar c2 = Calendar.getInstance();
                    c2.set(
                        Integer.parseInt(date.split("-")[0]),
                        Integer.parseInt(date.split("-")[1]) - 1,
                        Integer.parseInt(date.split("-")[2])
                    );

                    if (c2.before(c1)) {
                        continue;
                    }

                    JSONArray dayGames = games.getJSONObject(i).getJSONArray("games");
                    for (int j = 0; j < dayGames.length(); j++) {
                        JSONObject game = dayGames.getJSONObject(j);

                        // find if we're home or away
                        JSONObject away = game.getJSONObject("teams").getJSONObject("away").getJSONObject("team");
                        JSONObject home = game.getJSONObject("teams").getJSONObject("home").getJSONObject("team");

                        boolean isAway = away.getInt("id") == teamId;
                        String opponent = isAway ? home.getString("teamName") : away.getString("teamName");

                        String name = "%s %s - %s%s".formatted(isAway ? "@" : "vs", opponent, date, dayGames.length() > 1 ? " (Game %d)".formatted(j + 1) : "");
                        choices.add(new Command.Choice(name, game.getInt("gamePk")));
                    }
                }

                // Only get 25 choices
                if (choices.size() > 25) {
                    choices = choices.subList(0, 25);
                }

                event.replyChoices(choices).queue();

                return;
            }
        }

        event.replyChoices().queue();
    }

    public static List<Button> buildButtons(String gamePk) {
        return List.of(
            Button.success("plangame:start:"+gamePk, "Start"),
            Button.secondary("plangame:refresh:"+gamePk, "Refresh")
        );
    }

    private static void cleanDuplicates(List<String> list) {
        for (int i = 0; i < list.size(); i++) {
            String radioTeam = list.get(i).split(" - ")[0];
            for (int j = 0; j < list.size(); j++) {
                if (i == j) continue;
                String radioTeam2 = list.get(j).split(" - ")[0];
                if (radioTeam.equals(radioTeam2)) {
                    list.set(i, list.get(i) + ", " + list.get(j).split(" - ")[1]);
                    list.remove(j);
                }
            }
        }
    }

    private static class GameBlurb {
        String gamePk;
        JSONObject data;

        public GameBlurb(String gamePk) {
            this.gamePk = gamePk;

            // get da info
            this.data = new JSONObject(RestClient.get("https://statsapi.mlb.com/api/v1/schedule?language=en&gamePk=%s&hydrate=broadcasts(all),gameInfo,team,probablePitcher(all)&useLatestGames=true&fields=dates,date,games,gameDate,teams,away,probablePitcher,fullName,team,teamName,name,leagueRecord,wins,losses,pct,home,broadcasts,type,name,homeAway,isNational,callSign".formatted(gamePk)))
                .getJSONArray("dates")
                .getJSONObject(0)
                .getJSONArray("games")
                .getJSONObject(0);
        }

        public String name() {
            // Convert to Eastern Time, then to this format: "Feb 26th"
            ZoneId eastern = ZoneId.of("America/New_York");
            String date = time().atZoneSameInstant(eastern).format(DateTimeFormatter.ofPattern("MMM d"));

            // Teams
            JSONObject home = data.getJSONObject("teams").getJSONObject("home");
            JSONObject away = data.getJSONObject("teams").getJSONObject("away");

            String homeName = home.getJSONObject("team").getString("teamName");
            String awayName = away.getJSONObject("team").getString("teamName");

            return "%s @ %s - %s".formatted(awayName, homeName, date);
        }

        public String blurb() {
            return generateGameBlurb(gamePk, data);
        }

        public OffsetDateTime time() {
            // Format "2023-02-26T20:05:00Z" to OffsetDateTime
            TemporalAccessor accessor = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(data.getString("gameDate"));
            return OffsetDateTime.from(accessor);
        }
    }
}
