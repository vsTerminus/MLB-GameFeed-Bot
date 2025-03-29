# MLB-GameFeed-Bot

Add to your server with [this link](https://canary.discord.com/api/oauth2/authorize?client_id=987144502374436895&permissions=1067024&scope=bot%20applications.commands)!

To learn more about this bot and its commands, see [the knowledgebase on ChewHelp!](https://help.chew.pro/bots/discord/mlb-game-feed)

This bot allows you to view the live play-by-plays for any MLB.com game. This attempts to mimic [gameday](https://mlb.com/gameday)

To get started, add the bot to your server. Next, wait for a game to be active, finally, type `/startgame` to see active Major League games.

Supported Message Types:
- Score Changes
- Game Advisories (Injury Delay, Pitching changes, etc)
- Inning Changes

Commands:
- `/startgame [game]` - Starts a game. Select a game from the list, but any gamePk is acceptable. You can grab this from sites like https://mlb.chew.pw to show minor league games. If no games show up, there are no active Major League games.
- `/stopgame` - Stops the currently running game.
- `/score` - Shows you the current score privately.
- `/setinfo` - A command to show specific info as a Voice Channel name.

Running `/startgame` does not memorize the game, you will have to start it every time a game starts!

# Self-Hosting

Hosting your own instance of the bot is straightforward. You'll need:

- A Discord Bot Application Token
- A MySQL/MariaDB database
- Java 17+

A sample configuration file (`bot.properties.example`), a DB schema (`db-schema.sql`), and (for Linux users) a systemd service file (`mlb-gamefeed.service`) are provided to get you started.

## Register a new Discord Bot Application

Visit the Discord Developer Portal and register a New Application

- https://discord.com/developers/applications

Configure it however you like, but you will need to click the "Reset Token" button on the Bot page and record that value somewhere safe. Discord won't show it to you again.

## Upload Team Emoji

This bot will attempt to match uploaded emojis with teams in the league based on the team ID in the API. 

The images used by the author are unfortunately not provided in the repo, but at a minimum **you must** upload an emoji named `team_unknown_0` or the `standings` and `gameinfo` commands will not work.

### Team IDs

For the 2025 season you can find that list here: https://statsapi.mlb.com/api/v1/teams?sportIds=1,11,12,13,14&season=2025&fields=teams,id,name,clubName,active
The "id" value is the important part. Eg, the New York Yankees are `id: 147`.

### Emoji Names

In the Discord Developer Portal, your app has an "Emojis" page where you can upload custom emoji for the bot to use.

This bot expects the emojis to be named in a specific pattern:

- The name must begin with `team_`
- The name must end with an underscore and the team ID from the API, eg `_147`
- The middle is ignored and can be anything, but would recommend it be the team name, eg `yankees`

So some example Emoji Names would be:

- `team_brewers_158`
- `team_orioles_110`
- `team_reds_113`
- `team_yankees_147`

Additionally, the bot expects three additional emojis to be there:

- `team_american_league_all-stars_159`
- `team_national_league_all-stars_160`
- `team_unknown_0`

Again, you **must** have `team_unknown_0` or some commands will fail.

## Invite the Bot to Your Server

On the "General Information" page for your new application in the Discord Developer Portal, copy the "Application ID" value.

Navigate to the following URL in your browser, replacing "YOUR_CLIENT_ID_HERE" with the copied value.

`https://discord.com/oauth2/authorize?client_id=YOUR_CLIENT_ID_HERE&scope=bot`

This should prompt you to add the bot to any servers you manage.

## Find your Discord User ID

In Discord, open Settings -> Advanced and enable Developer Mode

Then Right click on your name in the channel or userlist or wherever and click "Copy ID"

## Create 'bot.properties'

Copy or rename "bot.properties.example" to "bot.properties" and edit the file with your favorite text editor.

Fill in your bot's Token, your own User ID (this tells the bot who its owner is), and update the database connection info appropriately

```ini
token = YourBotTokenHere-NoQuotesRequired
userId = The Numeric ID of the owner, eg 9999999999999

hibernate.connection.url = jdbc:mysql://localhost:3306/mlbgamefeed
hibernate.connection.username = mlbgamefeed
hibernate.connection.password = AVerySecurePassword
hibernate.connection.driver_class = com.mysql.cj.jdbc.Driver
```

## Initialize the Database

Assuming you already have MySQL/MariaDB running locally, you need to create a new database and a user for the bot to log in with.

Here are the command line steps, but you can use a graphical tool instead if you prefer.

```
mariadb -u root -p
CREATE DATABASE mlbgamefeed;
USE mlbgamefeed;
CREATE USER 'mlbgamefeed'@'localhost' IDENTIFIED BY 'AVerySecurePassword';
GRANT ALL ON mlbgamefeed TO 'mlbgamefeed'@'localhost';
exit
```

Now you can import the schema:

```sh
mariadb -u mlbgamefeed -p mlbgamefeed < db-schema.sql
```

Alternatively, you can manually create the tables:

```sql
USE mlbgamefeed;
CREATE TABLE servers ( id LONG NOT NULL, teamId INT);
CREATE TABLE channels ( id LONG NOT NULL, onlyScoringPlays BOOLEAN, gameAdvisories BOOLEAN, inPlayDelay INT DEFAULT 13, noPlayDelay INT DEFAULT 18, showScoreOnOut3 BOOLEAN);
```

## Run the Bot

Linux: `./gradlew run`
Windows: `.\gradlew.bat run`

The bot should show up as online and register its commands on the server. If you've missed anything the console output should point you in the right direction.

## Systemd Service (For Linux Users)

This only applies if you are using systemd. If you use OpenRC or some other init system you'll have to create the equivalent of this yourself.

1. Edit `mlbgamefeed.service` with the path to wherever your git repo is and with whatever user you want the service to run as.
2. Put it in `/etc/systemd/system/`.
3. Make systemd aware of the new service: `sudo systemctl daemon-reload`
4. Start the bot: `sudo systemctl start mlbgamfeed.service`
5. Make sure it worked: `systemctl status mlbgamefeed.service`
6. Enable it to start with the machine: `sudo systemctl enable mlbgamefeed.service`
