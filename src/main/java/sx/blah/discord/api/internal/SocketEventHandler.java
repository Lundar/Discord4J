package sx.blah.discord.api.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sx.blah.discord.Discord4J;
import sx.blah.discord.api.internal.json.event.*;
import sx.blah.discord.api.internal.json.objects.*;
import sx.blah.discord.api.internal.json.requests.IdentifyRequest;
import sx.blah.discord.api.internal.json.responses.*;
import sx.blah.discord.api.internal.json.responses.voice.VoiceUpdateResponse;
import sx.blah.discord.handle.impl.events.*;
import sx.blah.discord.handle.impl.obj.*;
import sx.blah.discord.handle.obj.*;
import sx.blah.discord.util.LogMarkers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SocketEventHandler {
	private DiscordWS ws;
	private DiscordClientImpl client;

	protected SocketEventHandler(DiscordWS ws, DiscordClientImpl client) {
		this.ws = ws;
		this.client = client;
	}

	public void onMessage(String message) {
		JsonParser parser = new JsonParser();
		JsonObject object = parser.parse(message).getAsJsonObject();
		if (object.has("message")) {
			String msg = object.get("message").getAsString();
			if (msg == null || msg.isEmpty()) {
				Discord4J.LOGGER.error(LogMarkers.WEBSOCKET, "Received unknown error from Discord. Frame: {}", message);
			} else
				Discord4J.LOGGER.error(LogMarkers.WEBSOCKET, "Received error from Discord: {}. Frame: {}", msg, message);
		}
		int op = object.get("op").getAsInt();

		if (object.has("s") && !object.get("s").isJsonNull())
			ws.seq = object.get("s").getAsLong();

		if (op == GatewayOps.DISPATCH.ordinal()) { //Event dispatched
			String type = object.get("t").getAsString();
			JsonElement eventObject = object.get("d");

			switch (type) {
				case "RESUMED": resumed(); break;
				case "READY": ready(eventObject); break;
				case "MESSAGE_CREATE": messageCreate(eventObject); break;
				case "TYPING_START": typingStart(eventObject); break;
				case "GUILD_CREATE": guildCreate(eventObject); break;
				case "GUILD_MEMBER_ADD": guildMemberAdd(eventObject); break;
				case "GUILD_MEMBER_REMOVE": guildMemberRemove(eventObject); break;
				case "GUILD_MEMBER_UPDATE": guildMemberUpdate(eventObject); break;
				case "MESSAGE_UPDATE": messageUpdate(eventObject); break;
				case "MESSAGE_DELETE": messageDelete(eventObject); break;
				case "MESSAGE_DELETE_BULK": messageDeleteBulk(eventObject); break;
				case "PRESENCE_UPDATE": presenceUpdate(eventObject); break;
				case "GUILD_DELETE": guildDelete(eventObject); break;
				case "CHANNEL_CREATE": channelCreate(eventObject); break;
				case "CHANNEL_DELETE": channelDelete(eventObject); break;
				case "CHANNEL_PINS_UPDATE": /* Implemented in MESSAGE_UPDATE. Ignored */ break;
				case "USER_UPDATE": userUpdate(eventObject); break;
				case "CHANNEL_UPDATE": channelUpdate(eventObject); break;
				case "GUILD_MEMBERS_CHUNK": guildMembersChunk(eventObject); break;
				case "GUILD_UPDATE": guildUpdate(eventObject); break;
				case "GUILD_ROLE_CREATE": guildRoleCreate(eventObject); break;
				case "GUILD_ROLE_UPDATE": guildRoleUpdate(eventObject); break;
				case "GUILD_ROLE_DELETE": guildRoleDelete(eventObject); break;
				case "GUILD_BAN_ADD": guildBanAdd(eventObject); break;
				case "GUILD_BAN_REMOVE": guildBanRemove(eventObject); break;
				case "GUILD_EMOJIS_UPDATE": /* TODO: Impl Emoji */ break;
				case "GUILD_INTEGRATIONS_UPDATE": /* TODO: Impl Guild integrations*/ break;
				case "VOICE_STATE_UPDATE": voiceStateUpdate(eventObject); break;
				case "VOICE_SERVER_UPDATE": voiceServerUpdate(eventObject); break;

				default:
					Discord4J.LOGGER.warn(LogMarkers.WEBSOCKET, "Unknown message received: {}, REPORT THIS TO THE DISCORD4J DEV! (ignoring): {}", type, message);
			}
		} else if (op == GatewayOps.HEARTBEAT.ordinal()) { //We received a heartbeat, time to send one back
			//ws.send(DiscordUtils.GSON.toJson(new KeepAliveRequest(ws.seq)));
		} else if (op == GatewayOps.RECONNECT.ordinal()) { //Gateway is redirecting us
			/*
			TODO: RECONNECT ME PLEASE
			 */
		} else if (op == GatewayOps.INVALID_SESSION.ordinal()) { //Invalid session ABANDON EVERYTHING!!!
			Discord4J.LOGGER.warn(LogMarkers.WEBSOCKET, "Invalid session! Attempting to clear caches and reconnect...");
			/*
			TODO: DISCONNECT
			 */
		} else if (op == GatewayOps.HELLO.ordinal()) {
			HelloResponse helloResponse = DiscordUtils.GSON.fromJson(object.get("d"), HelloResponse.class);
			ws.beginHeartbeat(helloResponse.heartbeat_interval);
			ws.send(GatewayOps.IDENTIFY, new IdentifyRequest(client.getToken()));
		} else if (op == GatewayOps.HEARTBEAT_ACK.ordinal()) {
			/*
			TODO: Discord is noticing us. Please acknowledge its acknowledgement of us
			 */
		} else {
			Discord4J.LOGGER.warn(LogMarkers.WEBSOCKET, "Unhandled opcode received: {} (ignoring), REPORT THIS TO THE DISCORD4J DEV!", op);
		}
	}

	private void resumed() {
		Discord4J.LOGGER.info(LogMarkers.WEBSOCKET, "Reconnected to the Discord websocket.");
		client.dispatcher.dispatch(new DiscordReconnectedEvent());
	}

	private void ready(JsonElement eventObject) {
		ReadyResponse ready = DiscordUtils.GSON.fromJson(eventObject, ReadyResponse.class);
		client.ourUser = DiscordUtils.getUserFromJSON(client, ready.user);

		//Arrays.stream(ready.private_channels).map(c -> DiscordUtils.getPrivateChannelFromJSON(client, c)).forEach(client.privateChannels::add);

		client.isReady = true;

	}

	private void messageCreate(JsonElement eventObject) {
		MessageObject event = DiscordUtils.GSON.fromJson(eventObject, MessageObject.class);
		boolean mentioned = event.mention_everyone;

		Channel channel = (Channel) client.getChannelByID(event.channel_id);

		if (null != channel) {
			if (!mentioned) { //Not worth checking if already mentioned
				for (UserObject user : event.mentions) { //Check mention array for a mention
					if (client.getOurUser().getID().equals(user.id)) {
						mentioned = true;
						break;
					}
				}
			}

			if (!mentioned) { //Not worth checking if already mentioned
				for (String role : event.mention_roles) { //Check roles for a mention
					if (client.getOurUser().getRolesForGuild(channel.getGuild()).contains(channel.getGuild().getRoleByID(role))) {
						mentioned = true;
						break;
					}
				}
			}

			IMessage message = DiscordUtils.getMessageFromJSON(client, channel, event);

			if (!channel.getMessages().contains(message)) {
				Discord4J.LOGGER.debug(LogMarkers.EVENTS, "Message from: {} ({}) in channel ID {}: {}", message.getAuthor().getName(),
						event.author.id, event.channel_id, event.content);

				List<String> invites = DiscordUtils.getInviteCodesFromMessage(event.content);
				if (invites.size() > 0) {
					String[] inviteCodes = invites.toArray(new String[invites.size()]);
					Discord4J.LOGGER.debug(LogMarkers.EVENTS, "Received invite codes \"{}\"", (Object) inviteCodes);
					List<IInvite> inviteObjects = new ArrayList<>();
					for (int i = 0; i < inviteCodes.length; i++) {
						IInvite invite = client.getInviteForCode(inviteCodes[i]);
						if (invite != null)
							inviteObjects.add(invite);
					}
					client.dispatcher.dispatch(new InviteReceivedEvent(inviteObjects.toArray(new IInvite[inviteObjects.size()]), message));
				}

				if (mentioned) {
					client.dispatcher.dispatch(new MentionEvent(message));
				}

				if (message.getAuthor().equals(client.getOurUser())) {
					client.dispatcher.dispatch(new MessageSendEvent(message));
					((Channel) message.getChannel()).setTypingStatus(false); //Messages being sent should stop the bot from typing
				} else {
					client.dispatcher.dispatch(new MessageReceivedEvent(message));
					if(!message.getEmbedded().isEmpty()) {
						client.dispatcher.dispatch(new MessageEmbedEvent(message, new ArrayList<>()));
					}
				}
			}
		}
	}

	private void typingStart(JsonElement eventObject) {
		TypingEventResponse event = DiscordUtils.GSON.fromJson(eventObject, TypingEventResponse.class);

		User user;
		Channel channel = (Channel) client.getChannelByID(event.channel_id);
		if (channel != null) {
			if (channel.isPrivate()) {
				user = (User) ((IPrivateChannel) channel).getRecipient();
			} else {
				user = (User) channel.getGuild().getUserByID(event.user_id);
			}

			if (user != null) {
				client.dispatcher.dispatch(new TypingEvent(user, channel));
			}
		}
	}

	private void guildCreate(JsonElement eventObject) {
		GuildObject event = DiscordUtils.GSON.fromJson(eventObject, GuildObject.class);
		if (event.unavailable) { //Guild can't be reached, so we ignore it
			Discord4J.LOGGER.warn(LogMarkers.WEBSOCKET, "Guild with id {} is unavailable, ignoring it. Is there an outage?", event.id);
			return;
		}

		Guild guild = (Guild) DiscordUtils.getGuildFromJSON(client, event);
		client.guildList.add(guild);
		client.dispatcher.dispatch(new GuildCreateEvent(guild));
		Discord4J.LOGGER.debug(LogMarkers.EVENTS, "New guild has been created/joined! \"{}\" with ID {}.", guild.getName(), guild.getID());
	}

	private void guildMemberAdd(JsonElement eventObject) {
		GuildMemberAddEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildMemberAddEventResponse.class);
		String guildID = event.guild_id;
		Guild guild = (Guild) client.getGuildByID(guildID);
		if (guild != null) {
			User user = (User) DiscordUtils.getUserFromGuildMemberResponse(client, guild, new MemberObject(event.user, event.roles));
			guild.addUser(user);
			LocalDateTime timestamp = DiscordUtils.convertFromTimestamp(event.joined_at);
			Discord4J.LOGGER.debug(LogMarkers.EVENTS, "User \"{}\" joined guild \"{}\".", user.getName(), guild.getName());
			client.dispatcher.dispatch(new UserJoinEvent(guild, user, timestamp));
		}
	}

	private void guildMemberRemove(JsonElement eventObject) {
		GuildMemberRemoveEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildMemberRemoveEventResponse.class);
		String guildID = event.guild_id;
		Guild guild = (Guild) client.getGuildByID(guildID);
		if (guild != null) {
			User user = (User) guild.getUserByID(event.user.id);
			if (user != null) {
				guild.getUsers().remove(user);
				guild.getJoinTimes().remove(user);
				Discord4J.LOGGER.debug(LogMarkers.EVENTS, "User \"{}\" has been removed from or left guild \"{}\".", user.getName(), guild.getName());
				client.dispatcher.dispatch(new UserLeaveEvent(guild, user));
			}
		}
	}

	private void guildMemberUpdate(JsonElement eventObject) {
		GuildMemberUpdateEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildMemberUpdateEventResponse.class);
		Guild guild = (Guild) client.getGuildByID(event.guild_id);
		User user = (User) client.getUserByID(event.user.id);

		if (guild != null && user != null) {
			List<IRole> oldRoles = new ArrayList<>(user.getRolesForGuild(guild));
			boolean rolesChanged = oldRoles.size() != event.roles.length+1;//Add one for the @everyone role
			if (!rolesChanged) {
				rolesChanged = oldRoles.stream().filter(role -> {
					if (role.equals(guild.getEveryoneRole()))
						return false;

					for (String roleID : event.roles) {
						if (role.getID().equals(roleID)) {
							return false;
						}
					}

					return true;
				}).collect(Collectors.toList()).size() > 0;
			}

			if (rolesChanged) {
				user.getRolesForGuild(guild).clear();
				for (String role : event.roles)
					user.addRole(guild.getID(), guild.getRoleByID(role));

				user.addRole(guild.getID(), guild.getEveryoneRole());

				client.dispatcher.dispatch(new UserRoleUpdateEvent(oldRoles, user.getRolesForGuild(guild), user, guild));
			}

			if (!user.getNicknameForGuild(guild).equals(Optional.ofNullable(event.nick))) {
				String oldNick = user.getNicknameForGuild(guild).orElse(null);
				user.addNick(guild.getID(), event.nick);

				client.dispatcher.dispatch(new NickNameChangeEvent(guild, user, oldNick, event.nick));
			}
		}
	}

	private void messageUpdate(JsonElement eventObject) {
		MessageObject event = DiscordUtils.GSON.fromJson(eventObject, MessageObject.class);
		String id = event.id;
		String channelID = event.channel_id;

		Channel channel = (Channel) client.getChannelByID(channelID);
		if (channel == null)
			return;

		Message toUpdate = (Message) channel.getMessageByID(id);
		if (toUpdate != null) {
			IMessage oldMessage = toUpdate.copy();

			toUpdate = (Message) DiscordUtils.getMessageFromJSON(client, channel, event);

			if (oldMessage.isPinned() && !event.pinned) {
				client.dispatcher.dispatch(new MessageUnpinEvent(toUpdate));
			} else if (!oldMessage.isPinned() && event.pinned) {
				client.dispatcher.dispatch(new MessagePinEvent(toUpdate));
			} else if (oldMessage.getEmbedded().size() < toUpdate.getEmbedded().size()) {
				client.dispatcher.dispatch(new MessageEmbedEvent(toUpdate, oldMessage.getEmbedded()));
			} else {
				client.dispatcher.dispatch(new MessageUpdateEvent(oldMessage, toUpdate));
			}
		}
	}

	private void messageDelete(JsonElement eventObject) {
		MessageDeleteEventResponse event = DiscordUtils.GSON.fromJson(eventObject, MessageDeleteEventResponse.class);
		String id = event.id;
		String channelID = event.channel_id;
		Channel channel = (Channel) client.getChannelByID(channelID);

		if (channel != null) {
			IMessage message = channel.getMessageByID(id);
			if (message != null) {
				if (message.isPinned()) {
					((Message) message).setPinned(false); //For consistency with the event
					client.dispatcher.dispatch(new MessageUnpinEvent(message));
				}

				client.dispatcher.dispatch(new MessageDeleteEvent(message));
			}
		}
	}

	private void messageDeleteBulk(JsonElement eventObject) { //TODO: maybe add a separate event for this?
		MessageDeleteBulkEventResponse event = DiscordUtils.GSON.fromJson(eventObject, MessageDeleteBulkEventResponse.class);
		for (String id : event.ids) {
			messageDelete(DiscordUtils.GSON.toJsonTree(new MessageDeleteEventResponse(id, event.channel_id)));
		}
	}

	private void presenceUpdate(JsonElement eventObject) {
		PresenceUpdateEventResponse event = DiscordUtils.GSON.fromJson(eventObject, PresenceUpdateEventResponse.class);
		Status status = DiscordUtils.getStatusFromJSON(event.game);
		Presences presence = status.getType() == Status.StatusType.STREAM ?
				Presences.STREAMING : Presences.valueOf(event.status.toUpperCase());
		Guild guild = (Guild) client.getGuildByID(event.guild_id);
		if (guild != null
				&& presence != null) {
			User user = (User) guild.getUserByID(event.user.id);
			if (user != null) {
				if (event.user.username != null) { //Full object was sent so there is a user change, otherwise all user fields but id would be null
					IUser oldUser = user.copy();
					user = DiscordUtils.getUserFromJSON(client, event.user);
					client.dispatcher.dispatch(new UserUpdateEvent(oldUser, user));
				}

				if (!user.getPresence().equals(presence)) {
					Presences oldPresence = user.getPresence();
					user.setPresence(presence);
					client.dispatcher.dispatch(new PresenceUpdateEvent(user, oldPresence, presence));
					Discord4J.LOGGER.debug(LogMarkers.EVENTS, "User \"{}\" changed presence to {}", user.getName(), user.getPresence());
				}
				if (!user.getStatus().equals(status)) {
					Status oldStatus = user.getStatus();
					user.setStatus(status);
					client.dispatcher.dispatch(new StatusChangeEvent(user, oldStatus, status));
					Discord4J.LOGGER.debug(LogMarkers.EVENTS, "User \"{}\" changed status to {}.", user.getName(), status);
				}
			}
		}
	}

	private void guildDelete(JsonElement eventObject) {
		GuildObject event = DiscordUtils.GSON.fromJson(eventObject, GuildObject.class);
		Guild guild = (Guild) client.getGuildByID(event.id);
		client.getGuilds().remove(guild);
		if (event.unavailable) { //Guild can't be reached
			Discord4J.LOGGER.warn(LogMarkers.WEBSOCKET, "Guild with id {} is unavailable, is there an outage?", event.id);
			client.dispatcher.dispatch(new GuildUnavailableEvent(event.id));
		} else {
			Discord4J.LOGGER.debug(LogMarkers.EVENTS, "You have been kicked from or left \"{}\"! :O", guild.getName());
			client.dispatcher.dispatch(new GuildLeaveEvent(guild));
		}
	}

	private void channelCreate(JsonElement eventObject) {
		boolean isPrivate = eventObject.getAsJsonObject().get("is_private").getAsBoolean();

		if (isPrivate) { // PM channel.
			PrivateChannelObject event = DiscordUtils.GSON.fromJson(eventObject, PrivateChannelObject.class);
			String id = event.id;
			boolean contained = false;
			for (IPrivateChannel privateChannel : client.privateChannels) {
				if (privateChannel.getID().equalsIgnoreCase(id))
					contained = true;
			}

			if (contained)
				return; // we already have this PM channel; no need to create another.

			client.privateChannels.add(DiscordUtils.getPrivateChannelFromJSON(client, event));

		} else { // Regular channel.
			ChannelObject event = DiscordUtils.GSON.fromJson(eventObject, ChannelObject.class);
			String type = event.type;
			Guild guild = (Guild) client.getGuildByID(event.guild_id);
			if (guild != null) {
				if (type.equalsIgnoreCase("text")) { //Text channel
					Channel channel = (Channel) DiscordUtils.getChannelFromJSON(client, guild, event);
					guild.addChannel(channel);
					client.dispatcher.dispatch(new ChannelCreateEvent(channel));
				} else if (type.equalsIgnoreCase("voice")) {
					VoiceChannel channel = (VoiceChannel) DiscordUtils.getVoiceChannelFromJSON(client, guild, event);
					guild.addVoiceChannel(channel);
					client.dispatcher.dispatch(new VoiceChannelCreateEvent(channel));
				}
			}
		}
	}

	private void channelDelete(JsonElement eventObject) {
		ChannelObject event = DiscordUtils.GSON.fromJson(eventObject, ChannelObject.class);
		if (event.type.equalsIgnoreCase("text")) {
			Channel channel = (Channel) client.getChannelByID(event.id);
			if (channel != null) {
				if (!channel.isPrivate())
					channel.getGuild().getChannels().remove(channel);
				else
					client.privateChannels.remove(channel);

				client.dispatcher.dispatch(new ChannelDeleteEvent(channel));
			}
		} else if (event.type.equalsIgnoreCase("voice")) {
			VoiceChannel channel = (VoiceChannel) client.getVoiceChannelByID(event.id);
			if (channel != null) {
				channel.getGuild().getVoiceChannels().remove(channel);
				client.dispatcher.dispatch(new VoiceChannelDeleteEvent(channel));
			}
		}
	}

	private void userUpdate(JsonElement eventObject) {
		UserUpdateEventResponse event = DiscordUtils.GSON.fromJson(eventObject, UserUpdateEventResponse.class);
		User newUser = (User) client.getUserByID(event.id);
		if (newUser != null) {
			IUser oldUser = newUser.copy();
			newUser = DiscordUtils.getUserFromJSON(client, event);
			client.dispatcher.dispatch(new UserUpdateEvent(oldUser, newUser));
		}
	}

	private void channelUpdate(JsonElement eventObject) {
		ChannelUpdateEventResponse event = DiscordUtils.GSON.fromJson(eventObject, ChannelUpdateEventResponse.class);
		if (!event.is_private) {
			if (event.type.equalsIgnoreCase("text")) {
				Channel toUpdate = (Channel) client.getChannelByID(event.id);
				if (toUpdate != null) {
					IChannel oldChannel = toUpdate.copy();

					toUpdate = (Channel) DiscordUtils.getChannelFromJSON(client, toUpdate.getGuild(), event);

					client.getDispatcher().dispatch(new ChannelUpdateEvent(oldChannel, toUpdate));
				}
			} else if (event.type.equalsIgnoreCase("voice")) {
				VoiceChannel toUpdate = (VoiceChannel) client.getVoiceChannelByID(event.id);
				if (toUpdate != null) {
					VoiceChannel oldChannel = (VoiceChannel) toUpdate.copy();

					toUpdate = (VoiceChannel) DiscordUtils.getVoiceChannelFromJSON(client, toUpdate.getGuild(), event);

					client.getDispatcher().dispatch(new VoiceChannelUpdateEvent(oldChannel, toUpdate));
				}
			}
		}
	}

	private void guildMembersChunk(JsonElement eventObject) {
		GuildMemberChunkEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildMemberChunkEventResponse.class);
		Guild guildToUpdate = (Guild) client.getGuildByID(event.guild_id);
		if (guildToUpdate == null) {
			Discord4J.LOGGER.warn(LogMarkers.WEBSOCKET, "Can't receive guild members chunk for guild id {}, the guild is null!", event.guild_id);
			return;
		}

		for (MemberObject member : event.members) {
			IUser user = DiscordUtils.getUserFromGuildMemberResponse(client, guildToUpdate, member);
			guildToUpdate.addUser(user);
		}
	}

	private void guildUpdate(JsonElement eventObject) {
		GuildObject guildResponse = DiscordUtils.GSON.fromJson(eventObject, GuildObject.class);
		Guild toUpdate = (Guild) client.getGuildByID(guildResponse.id);

		if (toUpdate != null) {
			IGuild oldGuild = toUpdate.copy();

			toUpdate = (Guild) DiscordUtils.getGuildFromJSON(client, guildResponse);

			if (!toUpdate.getOwnerID().equals(oldGuild.getOwnerID())) {
				client.dispatcher.dispatch(new GuildTransferOwnershipEvent(oldGuild.getOwner(), toUpdate.getOwner(), toUpdate));
			} else {
				client.dispatcher.dispatch(new GuildUpdateEvent(oldGuild, toUpdate));
			}
		}
	}

	private void guildRoleCreate(JsonElement eventObject) {
		GuildRoleEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildRoleEventResponse.class);
		IGuild guild = client.getGuildByID(event.guild_id);
		if (guild != null) {
			IRole role = DiscordUtils.getRoleFromJSON(guild, event.role);
			client.dispatcher.dispatch(new RoleCreateEvent(role, guild));
		}
	}

	private void guildRoleUpdate(JsonElement eventObject) {
		GuildRoleEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildRoleEventResponse.class);
		IGuild guild = client.getGuildByID(event.guild_id);
		if (guild != null) {
			IRole toUpdate = guild.getRoleByID(event.role.id);
			if (toUpdate != null) {
				IRole oldRole = toUpdate.copy();
				toUpdate = DiscordUtils.getRoleFromJSON(guild, event.role);
				client.dispatcher.dispatch(new RoleUpdateEvent(oldRole, toUpdate, guild));
			}
		}
	}

	private void guildRoleDelete(JsonElement eventObject) {
		GuildRoleDeleteEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildRoleDeleteEventResponse.class);
		IGuild guild = client.getGuildByID(event.guild_id);
		if (guild != null) {
			IRole role = guild.getRoleByID(event.role_id);
			if (role != null) {
				guild.getRoles().remove(role);
				client.dispatcher.dispatch(new RoleDeleteEvent(role, guild));
			}
		}
	}

	private void guildBanAdd(JsonElement eventObject) {
		GuildBanEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildBanEventResponse.class);
		IGuild guild = client.getGuildByID(event.guild_id);
		if (guild != null) {
			IUser user = DiscordUtils.getUserFromJSON(client, event.user);
			if (client.getUserByID(user.getID()) != null) {
				guild.getUsers().remove(user);
				((Guild) guild).getJoinTimes().remove(user);
			}

			client.dispatcher.dispatch(new UserBanEvent(user, guild));
		}
	}

	private void guildBanRemove(JsonElement eventObject) {
		GuildBanEventResponse event = DiscordUtils.GSON.fromJson(eventObject, GuildBanEventResponse.class);
		IGuild guild = client.getGuildByID(event.guild_id);
		if (guild != null) {
			IUser user = DiscordUtils.getUserFromJSON(client, event.user);

			client.dispatcher.dispatch(new UserPardonEvent(user, guild));
		}
	}

	private void voiceStateUpdate(JsonElement eventObject) {
		VoiceStateObject event = DiscordUtils.GSON.fromJson(eventObject, VoiceStateObject.class);
		IGuild guild = client.getGuildByID(event.guild_id);

		if (guild != null) {
			IVoiceChannel channel = guild.getVoiceChannelByID(event.channel_id);
			User user = (User) guild.getUserByID(event.user_id);
			if (user != null) {
				user.setIsDeaf(guild.getID(), event.deaf);
				user.setIsMute(guild.getID(), event.mute);
				user.setIsDeafLocally(event.self_deaf);
				user.setIsMutedLocally(event.self_mute);

				IVoiceChannel oldChannel = user.getConnectedVoiceChannels()
						.stream()
						.filter(vChannel -> vChannel.getGuild().getID().equals(event.guild_id))
						.findFirst()
						.orElse(null);
				if (oldChannel == null)
					oldChannel = user.getConnectedVoiceChannels()
							.stream()
							.findFirst()
							.orElse(null);
				if (channel != oldChannel) {
					if (channel == null) {
						client.dispatcher.dispatch(new UserVoiceChannelLeaveEvent(user, oldChannel));
						user.getConnectedVoiceChannels().remove(oldChannel);
					} else if (oldChannel != null && oldChannel.getGuild().equals(channel.getGuild())) {
						client.dispatcher.dispatch(new UserVoiceChannelMoveEvent(user, oldChannel, channel));
						user.getConnectedVoiceChannels().remove(oldChannel);
						if (!user.getConnectedVoiceChannels().contains(channel))
							user.getConnectedVoiceChannels().add(channel);
					} else {
						client.dispatcher.dispatch(new UserVoiceChannelJoinEvent(user, channel));
						if (!user.getConnectedVoiceChannels().contains(channel))
							user.getConnectedVoiceChannels().add(channel);
					}
				}
			}
		}
	}

	private void voiceServerUpdate(JsonElement eventObject) {
		VoiceUpdateResponse event = DiscordUtils.GSON.fromJson(eventObject, VoiceUpdateResponse.class);
		try {
			event.endpoint = event.endpoint.substring(0, event.endpoint.indexOf(":"));
			client.voiceConnections.put(client.getGuildByID(event.guild_id), DiscordVoiceWS.connect(event, client));
		} catch (Exception e) {
			Discord4J.LOGGER.error(LogMarkers.VOICE_WEBSOCKET, "Discord4J Internal Exception", e);
		}
	}
}