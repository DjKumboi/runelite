/*
 * Copyright (c) 2019, winterdaze
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.tzhaartimers;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;
import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import static net.runelite.api.ItemID.FIRE_CAPE;
import static net.runelite.api.ItemID.INFERNAL_CAPE;

@PluginDescriptor(
	name = "Tzhaar Timers",
	description = "Display elapsed time in the Fight Caves and Inferno",
	tags = {"inferno", "fight", "caves", "cape", "timer", "tzhaar"}
)
@Slf4j
public class TzhaarTimersPlugin extends Plugin
{
	private static final String START_MESSAGE = "Wave: 1";
	private static final String WAVE_MESSAGE = "Wave:";
	private static final String DEFEATED_MESSAGE = "You have been defeated!";
	private static final String INFERNO_COMPLETE_MESSAGE = "Your TzKal-Zuk kill count is:";
	private static final String FIGHT_CAVES_COMPLETE_MESSAGE = "Your TzTok-Jad kill count is:";
	private static final String INFERNO_PAUSED_MESSAGE = "The Inferno has been paused. You may now log out.";
	private static final String FIGHT_CAVE_PAUSED_MESSAGE = "The Fight Cave has been paused. You may now log out.";

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private Client client;

	@Inject
	private TzhaarTimersConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Getter
	private TzhaarTimers timer;

	private Instant startTime;

	private Instant lastTime;

	private boolean started;

	private boolean loggingIn;

	@Provides
	TzhaarTimersConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TzhaarTimersConfig.class);
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeTimer();
		resetConfig();
		startTime = null;
		lastTime = null;
		started = false;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("tzhaartimers"))
		{
			return;
		}

		if (event.getKey().equals("tzhaarTimers"))
		{
			updateInfoBoxState();
			return;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		log.info(event.getGameState().toString());
		switch (event.getGameState())
		{
			case LOGGED_IN:
				if (loggingIn)
				{
					loggingIn = false;
					loadConfig();
					resetConfig();
				}
				break;
			case LOGGING_IN:
				loggingIn = true;
				break;
			case LOADING:
				if (!loggingIn)
				{
					updateInfoBoxState();
				}
				break;
			case HOPPING:
				loggingIn = true;
			case LOGIN_SCREEN:
				removeTimer();
				saveConfig();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (config.tzhaarTimers() && (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM))
		{
			String message = Text.removeTags(event.getMessage());
			Instant now = Instant.now();
			if (!started && message.contains(START_MESSAGE))
			{
				started = true;
				if (checkInFightCaves())
				{
					createTimer(FIRE_CAPE, now, null);
				}
				if (checkInInferno())
				{
					createTimer(INFERNAL_CAPE, now, null);
				}
				startTime = now;
				return;
			}
			if (started)
			{
				if (message.contains(WAVE_MESSAGE))
				{
					if (lastTime != null)
					{
						startTime = startTime.plus(Duration.between(startTime, now)).minus(Duration.between(startTime, lastTime));
						lastTime = null;
					}
					if (checkInFightCaves())
					{
						infoBoxManager.removeInfoBox(timer);
						createTimer(FIRE_CAPE, startTime, lastTime);
					}
					if (checkInInferno())
					{
						infoBoxManager.removeInfoBox(timer);
						createTimer(INFERNAL_CAPE, startTime, lastTime);
					}
				}
				if (message.contains(FIGHT_CAVE_PAUSED_MESSAGE) || message.contains(INFERNO_PAUSED_MESSAGE))
				{
					if (checkInFightCaves())
					{
						infoBoxManager.removeInfoBox(timer);
						createTimer(FIRE_CAPE, startTime, now);
					}
					if (checkInInferno())
					{
						infoBoxManager.removeInfoBox(timer);
						createTimer(INFERNAL_CAPE, startTime, now);
					}
					lastTime = now;
				}
				if (message.contains(DEFEATED_MESSAGE) || message.contains(INFERNO_COMPLETE_MESSAGE) || message.contains(FIGHT_CAVES_COMPLETE_MESSAGE))
				{
					removeTimer();
					resetConfig();
					startTime = null;
					lastTime = null;
					started = false;
				}
			}
		}
	}

	private void updateInfoBoxState()
	{
		if (timer == null)
		{
			return;
		}

		if ((!checkInFightCaves() && !checkInInferno()) || !config.tzhaarTimers())
		{
			removeTimer();
			resetConfig();
			startTime = null;
			lastTime = null;
			started = false;
		}
	}

	private boolean checkInFightCaves()
	{
		return client.getMapRegions() != null && Arrays.stream(client.getMapRegions())
				.filter(x -> x == 9551)
				.toArray().length > 0;
	}

	private boolean checkInInferno()
	{
		return client.getMapRegions() != null && Arrays.stream(client.getMapRegions())
				.filter(x -> x == 9043)
				.toArray().length > 0;
	}

	private void removeTimer()
	{
		infoBoxManager.removeInfoBox(timer);
		timer = null;
	}

	private void createTimer(int id, Instant time, Instant lTime)
	{
		timer = new TzhaarTimers(itemManager.getImage(id), this, time, lTime);
		infoBoxManager.addInfoBox(timer);
	}

	private void loadConfig()
	{
		startTime = configManager.getConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_TIME, Instant.class);
		started = configManager.getConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_STARTED, Boolean.class);
		lastTime = configManager.getConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_LASTTIME, Instant.class);
	}

	private void resetConfig()
	{
		configManager.unsetConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_TIME);
		configManager.unsetConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_STARTED);
		configManager.unsetConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_LASTTIME);
	}

	private void saveConfig()
	{
		if (startTime != null)
		{
			resetConfig();
			if (lastTime == null)
			{
				lastTime = Instant.now();
			}
			configManager.setConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_TIME, startTime);
			configManager.setConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_STARTED, started);
			configManager.setConfiguration(TzhaarTimersConfig.CONFIG_GROUP, TzhaarTimersConfig.CONFIG_LASTTIME, lastTime);
			startTime = null;
			lastTime = null;
			started = false;
		}
	}
}
