/*******************************************************************************
 * OpenEMS - Open Source Energy Management System
 * Copyright (c) 2016, 2017 FENECON GmbH and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *   FENECON GmbH - initial API and implementation and initial documentation
 *******************************************************************************/
package io.openems.core.utilities.websocket;

import java.io.IOException;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.api.bridge.Bridge;
import io.openems.api.channel.Channel;
import io.openems.api.channel.ConfigChannel;
import io.openems.api.device.Device;
import io.openems.api.exception.ConfigException;
import io.openems.api.exception.NotImplementedException;
import io.openems.api.exception.OpenemsException;
import io.openems.api.persistence.QueryablePersistence;
import io.openems.api.thing.Thing;
import io.openems.core.Config;
import io.openems.core.Databus;
import io.openems.core.ThingRepository;
import io.openems.core.utilities.JsonUtils;
import io.openems.core.utilities.StringUtils;

/**
 * Handles a Websocket connection to a browser, femsserver,...
 *
 * @author stefan.feilmeier
 */
public class WebsocketHandler {

	protected final static String DEFAULT_DEVICE_NAME = "fems";

	private static Logger log = LoggerFactory.getLogger(WebsocketHandler.class);

	/**
	 * Holds thingId and channelId, subscribed by this websocket
	 */
	private final HashMultimap<String, String> subscribedChannels = HashMultimap.create();

	/**
	 * Executor for subscriptions task
	 */
	private static ScheduledExecutorService subscriptionExecutor = Executors.newScheduledThreadPool(1);

	/**
	 * Task regularly send subscriped to data
	 */
	private final Runnable subscriptionTask;

	/**
	 * Holds the scheduled subscription task
	 */
	private ScheduledFuture<?> subscriptionFuture = null;

	/**
	 * Holds the databus singleton
	 */
	private final Databus databus;

	/**
	 * Holds the websocket connection
	 */
	protected final WebSocket websocket;

	private final ThingRepository thingRepository;

	private WebsocketHandler handler;

	public WebsocketHandler(WebSocket websocket) {
		this.databus = Databus.getInstance();
		this.websocket = websocket;
		this.thingRepository = ThingRepository.getInstance();
		this.subscriptionTask = () -> {
			/*
			 * This task is executed regularly. Sends data to websocket.
			 */
			JsonObject j = new JsonObject();
			JsonObject jCurrentdata = getSubscribedData();
			j.add("currentdata", jCurrentdata);
			this.send(j);
		};
	}

	/**
	 * Message event of websocket. Handles a new message.
	 */
	public void onMessage(JsonObject jMessage) {
		/*
		 * Subscribe to data
		 */
		if (jMessage.has("subscribe")) {
			subscribe(jMessage.get("subscribe"));
		}

		/*
		 * Configuration
		 */
		if (jMessage.has("configure")) {
			configure(jMessage.get("configure"));
		}

		/*
		 * System command
		 */
		if (jMessage.has("system")) {
			system(jMessage.get("system"));
		}

		/*
		 * Query command
		 */
		if (jMessage.has("query")) {
			query(jMessage.get("query"));
		}
	}

	/**
	 * Handle subscriptions
	 *
	 * @param j
	 */
	private synchronized void subscribe(JsonElement jSubscribeElement) {
		try {
			// unsubscribe regular task
			if (subscriptionFuture != null) {
				subscriptionFuture.cancel(true);
			}
			// clear subscriptions
			this.subscribedChannels.clear();
			// add subscriptions
			JsonObject jSubscribe = JsonUtils.getAsJsonObject(jSubscribeElement);
			jSubscribe.entrySet().forEach(entry -> {
				try {
					String thing = entry.getKey();
					JsonArray jChannels = JsonUtils.getAsJsonArray(entry.getValue());
					for (JsonElement jChannel : jChannels) {
						String channel = JsonUtils.getAsString(jChannel);
						this.subscribedChannels.put(thing, channel);
					}
				} catch (OpenemsException e) {
					log.error(e.getMessage());
				}
			});
			// schedule task
			if (!this.subscribedChannels.isEmpty()) {
				subscriptionFuture = subscriptionExecutor.scheduleWithFixedDelay(this.subscriptionTask, 0, 3,
						TimeUnit.SECONDS);
			}
		} catch (OpenemsException e) {
			log.error(e.getMessage());
		}
	}

	/**
	 * Gets a json object with all subscribed channels
	 *
	 * @return
	 */
	private JsonObject getSubscribedData() {
		JsonObject jData = new JsonObject();
		subscribedChannels.keys().forEach(thingId -> {
			JsonObject jThingData = new JsonObject();
			subscribedChannels.get(thingId).forEach(channelId -> {
				Optional<?> value = databus.getValue(thingId, channelId);
				JsonElement jValue;
				try {
					jValue = JsonUtils.getAsJsonElement(value.orElse(null));
					jThingData.add(channelId, jValue);
				} catch (NotImplementedException e) {
					log.error(e.getMessage());
				}
			});
			jData.add(thingId, jThingData);
		});
		return jData;
	}

	/**
	 * Sends a message to the websocket
	 *
	 * @param jMessage
	 */
	public boolean send(JsonObject jMessage) {
		try {
			this.websocket.send(jMessage.toString());
			return true;
		} catch (WebsocketNotConnectedException e) {
			return false;
		}
	}

	/**
	 * Sends an initial message to the browser after it was successfully connected
	 */
	public boolean sendConnectionSuccessfulReply() {
		JsonObject j = this.createConnectionSuccessfulReply();
		log.info("Send Connection Successful Reply: " + StringUtils.toShortString(j, 100));
		return this.send(j);
	}

	/**
	 * Creates an initial message to the browser after it was successfully connected
	 *
	 * <pre>
	 * {
	 *   metadata: {
	 *       config: {...},
	 *       backend: "openems"
	 *   }
	 * }
	 * </pre>
	 *
	 * @param handler
	 */
	protected JsonObject createConnectionSuccessfulReply() {
		JsonObject j = new JsonObject();

		// Metadata
		JsonObject jMetadata = new JsonObject();
		try {
			jMetadata.add("config", Config.getInstance().getMetaConfigJson());
		} catch (ConfigException e) {
			log.error(e.getMessage());
		}
		jMetadata.addProperty("backend", "openems");
		j.add("metadata", jMetadata);

		return j;
	}

	/**
	 * Set configuration
	 *
	 * @param j
	 */
	private synchronized void configure(JsonElement jConfigsElement) {
		try {
			JsonArray jConfigs = JsonUtils.getAsJsonArray(jConfigsElement);
			ThingRepository thingRepository = ThingRepository.getInstance();
			JsonObject jNotification = new JsonObject();
			for (JsonElement jConfigElement : jConfigs) {
				JsonObject jConfig = JsonUtils.getAsJsonObject(jConfigElement);
				String mode = JsonUtils.getAsString(jConfig, "mode");
				if (mode.equals("update")) {
					/*
					 * Channel Set mode
					 */
					String thingId = JsonUtils.getAsString(jConfig, "thing");
					String channelId = JsonUtils.getAsString(jConfig, "channel");
					JsonElement jValue = JsonUtils.getSubElement(jConfig, "value");
					Optional<Channel> channelOptional = thingRepository.getChannel(thingId, channelId);
					if (channelOptional.isPresent()) {
						Channel channel = channelOptional.get();
						if (channel instanceof ConfigChannel<?>) {
							ConfigChannel<?> configChannel = (ConfigChannel<?>) channel;
							configChannel.updateValue(jValue, true);
							log.info("Updated channel " + channel.address() + " with " + jValue);
							// TODO send notification

							jNotification = createNotification(NotificationType.SUCCESS,
									"Successfully updated [" + channel.address() + "] to [" + jValue + "]");

						}
					} else {
						throw new ConfigException("Unable to find " + jConfig.toString());
					}
				} else if (mode.equals("create")) {
					/*
					 * Create new Thing
					 */
					JsonObject jObject = JsonUtils.getAsJsonObject(jConfig, "object");
					String parentId = JsonUtils.getAsString(jConfig, "parent");
					String thingId = JsonUtils.getAsString(jObject, "id");
					if (thingId.startsWith("_")) {
						throw new ConfigException("IDs starting with underscore are reserved for internal use.");
					}
					if (thingRepository.getThingById(thingId).isPresent()) {
						throw new ConfigException("Thing Id is already existing.");
					}
					String clazzName = JsonUtils.getAsString(jObject, "class");
					Class<?> clazz = Class.forName(clazzName);
					if (Device.class.isAssignableFrom(clazz)) {
						// Device
						Thing parentThing = thingRepository.getThing(parentId);
						if (parentThing instanceof Bridge) {
							Bridge parentBridge = (Bridge) parentThing;
							Device device = thingRepository.createDevice(jObject);
							parentBridge.addDevice(device);
							Config.getInstance().writeConfigFile();
							log.info("Device [" + device.id() + "] wurde erstellt.");
							// TODO send notification
							jNotification = createNotification(NotificationType.SUCCESS,
									"Device [" + device.id() + "] wurde erstellt.");
							break;
						}
					}
				} else if (mode.equals("delete")) {
					/*
					 * Delete a Thing
					 */
					String thingId = JsonUtils.getAsString(jConfig, "thing");
					thingRepository.removeThing(thingId);
					Config.getInstance().writeConfigFile();
					// TODO send notification
					jNotification = createNotification(NotificationType.SUCCESS,
							"Controller [" + thingId + "] wurde " + " gel�scht.");
					log.info("Controller [" + thingId + "] wurde gelöscht.");
				} else {
					throw new OpenemsException("Modus [" + mode + "] ist nicht implementiert.");
				}
			}
			// Send new config
			JsonObject jMetadata = new JsonObject();
			jMetadata.add("config", Config.getInstance().getMetaConfigJson());
			JsonObject j = new JsonObject();
			j.add("metadata", jMetadata);
			j.add("notification", jNotification);
			this.send(j);
		} catch (OpenemsException | ClassNotFoundException e) {
			log.error(e.getMessage());
			this.createNotification(NotificationType.ERROR, e.getMessage());
			// TODO: send notification to websocket
		}
	}

	/**
	 * System command
	 *
	 * @param j
	 */
	private synchronized void system(JsonElement jSystemElement) {
		try {
			JsonObject jSystem = JsonUtils.getAsJsonObject(jSystemElement);
			String mode = JsonUtils.getAsString(jSystem, "mode");
			if (mode.equals("systemd-restart")) {
				String service = JsonUtils.getAsString(jSystem, "service");
				if (service.equals("fems-pagekite")) {
					ProcessBuilder builder = new ProcessBuilder("/bin/systemctl", "restart", "fems-pagekite");
					Process p = builder.start();
					if (p.waitFor() == 0) {
						log.info("Successfully restarted fems-pagekite");
					} else {
						throw new OpenemsException("restart fems-pagekite failed");
					}
				} else {
					throw new OpenemsException("Unknown systemd-restart service: " + jSystemElement.toString());
				}
			} else {
				throw new OpenemsException("Unknown system message: " + jSystemElement.toString());
			}
		} catch (OpenemsException | IOException | InterruptedException e) {
			log.error(e.getMessage());
		}
	}

	/**
	 * Query command
	 *
	 * @param j
	 */
	private synchronized void query(JsonElement jQueryElement) {
		try {
			JsonObject jQuery = JsonUtils.getAsJsonObject(jQueryElement);
			String mode = JsonUtils.getAsString(jQuery, "mode");
			if (mode.equals("history")) {
				/*
				 * History query
				 */
				int timezoneDiff = JsonUtils.getAsInt(jQuery, "timezone");
				ZoneId timezone = ZoneId.ofOffset("", ZoneOffset.ofTotalSeconds(timezoneDiff * -1));
				ZonedDateTime fromDate = JsonUtils.getAsZonedDateTime(jQuery, "fromDate", timezone);
				ZonedDateTime toDate = JsonUtils.getAsZonedDateTime(jQuery, "toDate", timezone);
				JsonObject channels = JsonUtils.getAsJsonObject(jQuery, "channels");
				JsonObject kWh = JsonUtils.getAsJsonObject(jQuery, "kWh");
				// Calculate resolution
				int days = Period.between(fromDate.toLocalDate(), toDate.toLocalDate()).getDays();
				int resolution = 60 * 60; // 60 Minutes
				if (days > 6) {
					resolution = 24 * 60 * 60; // 60 Minutes
				}
				JsonObject jQueryreply = null;
				for (QueryablePersistence queryablePersistence : thingRepository.getQueryablePersistences()) {
					jQueryreply = queryablePersistence.query(fromDate, toDate, channels, resolution, kWh);
					if (jQueryreply != null) {
						break;
					}
				}
				// Send result
				JsonObject j = new JsonObject();
				j.add("queryreply", jQueryreply);
				this.send(j);

				log.info("RESULT: " + j);
			}
		} catch (OpenemsException e) {
			log.error(e.getMessage());
		}
	}

	/*
	 * private void manualPQ(JsonElement j, AuthenticatedWebsocketHandler handler) {
	 * try {
	 * JsonObject jPQ = JsonUtils.getAsJsonObject(j);
	 * if (jPQ.has("p") && jPQ.has("q")) {
	 * long p = JsonUtils.getAsLong(jPQ, "p");
	 * long q = JsonUtils.getAsLong(jPQ, "q");
	 * this.controller.setManualPQ(p, q);
	 * handler.sendNotification(NotificationType.SUCCESS, "Leistungsvorgabe gesetzt: P=" + p + ",Q=" + q);
	 * } else {
	 * // stop manual PQ
	 * this.controller.resetManualPQ();
	 * handler.sendNotification(NotificationType.SUCCESS, "Leistungsvorgabe zurückgesetzt");
	 * }
	 * } catch (ReflectionException e) {
	 * handler.sendNotification(NotificationType.SUCCESS, "Leistungsvorgabewerte falsch: " + e.getMessage());
	 * }
	 * }
	 */
	// private void channel(JsonElement jChannelElement, AuthenticatedWebsocketHandler handler) {
	// try {
	// JsonObject jChannel = JsonUtils.getAsJsonObject(jChannelElement);
	// String thingId = JsonUtils.getAsString(jChannel, "thing");
	// String channelId = JsonUtils.getAsString(jChannel, "channel");
	// JsonElement jValue = JsonUtils.getSubElement(jChannel, "value");
	//
	// // get channel
	// Channel channel;
	// Optional<Channel> channelOptional = thingRepository.getChannel(thingId, channelId);
	// if (channelOptional.isPresent()) {
	// // get channel value
	// channel = channelOptional.get();
	// } else {
	// // Channel not found
	// throw new ResourceException(Status.CLIENT_ERROR_NOT_FOUND);
	// }
	//
	// // check for writable channel
	// if (!(channel instanceof WriteChannel<?>)) {
	// throw new ResourceException(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
	// }
	//
	// // set channel value
	// if (channel instanceof ConfigChannel<?>) {
	// // is a ConfigChannel
	// ConfigChannel<?> configChannel = (ConfigChannel<?>) channel;
	// try {
	// configChannel.updateValue(jValue, true);
	// log.info("Updated Channel [" + channel.address() + "] to value [" + jValue.toString() + "].");
	// handler.sendNotification(NotificationType.SUCCESS,
	// "Channel [" + channel.address() + "] aktualisiert zu [" + jValue.toString() + "].");
	// } catch (NotImplementedException e) {
	// throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST, "Conversion not implemented");
	// }
	// } else {
	// // is a WriteChannel
	// handler.sendNotification(NotificationType.WARNING, "WriteChannel nicht implementiert");
	// }
	// } catch (ReflectionException e) {
	// handler.sendNotification(NotificationType.SUCCESS, "Leistungsvorgabewerte falsch: " + e.getMessage());
	// }
	// }

	/**
	 * Send a notification message/error to the websocket
	 *
	 * @param mesage
	 * @return true if successful, otherwise false
	 */
	// TODO
	public synchronized JsonObject createNotification(NotificationType type, String message) {
		// log message to syslog
		switch (type) {
		case INFO:
		case SUCCESS:
			log.info(message);
			break;
		case ERROR:
			log.error(message);
			break;
		case WARNING:
			log.warn(message);
			break;
		}
		// send notification to websocket
		JsonObject jMessage = new JsonObject();
		jMessage.addProperty("type", type.name().toLowerCase());
		jMessage.addProperty("message", message);
		return jMessage;
	}
}