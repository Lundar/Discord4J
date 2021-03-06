package sx.blah.discord.handle.impl.obj;

import com.google.gson.Gson;
import org.apache.http.message.BasicNameValuePair;
import sx.blah.discord.Discord4J;
import sx.blah.discord.api.internal.DiscordClientImpl;
import sx.blah.discord.api.internal.DiscordEndpoints;
import sx.blah.discord.util.DiscordException;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.handle.obj.IInvite;
import sx.blah.discord.api.internal.json.responses.InviteJSONResponse;
import sx.blah.discord.util.RateLimitException;
import sx.blah.discord.util.LogMarkers;

import java.util.Objects;

public class Invite implements IInvite {
	/**
	 * An invite code, AKA an invite URL minus the https://discord.gg/
	 */
	protected final String inviteCode;

	/**
	 * The client that created this object.
	 */
	protected final IDiscordClient client;

	public Invite(IDiscordClient client, String inviteCode) {
		this.client = client;
		this.inviteCode = inviteCode;
	}

	@Override
	public String getInviteCode() {
		return inviteCode;
	}

	@Override
	public InviteResponse accept() throws DiscordException, RateLimitException {
		if (client.isBot())
			throw new DiscordException("This action can only be performed by a user");

		if (client.isReady()) {
			String response = ((DiscordClientImpl) client).REQUESTS.POST.makeRequest(DiscordEndpoints.INVITE+inviteCode,
					new BasicNameValuePair("authorization", client.getToken()));

			InviteJSONResponse inviteResponse = new Gson().fromJson(response, InviteJSONResponse.class);

			return new InviteResponse(inviteResponse.guild.id, inviteResponse.guild.name,
					inviteResponse.channel.id, inviteResponse.channel.name);
		} else {
			Discord4J.LOGGER.error(LogMarkers.HANDLE, "Bot has not signed in yet!");
			return null;
		}
	}

	@Override
	public InviteResponse details() throws DiscordException, RateLimitException {
		if (client.isReady()) {
			String response = ((DiscordClientImpl) client).REQUESTS.GET.makeRequest(DiscordEndpoints.INVITE+inviteCode,
					new BasicNameValuePair("authorization", client.getToken()));

			InviteJSONResponse inviteResponse = new Gson().fromJson(response, InviteJSONResponse.class);

			return new InviteResponse(inviteResponse.guild.id, inviteResponse.guild.name,
					inviteResponse.channel.id, inviteResponse.channel.name);
		} else {
			Discord4J.LOGGER.error(LogMarkers.HANDLE, "Bot has not signed in yet!");
			return null;
		}
	}

	@Override
	public void delete() throws RateLimitException, DiscordException {
		((DiscordClientImpl) client).REQUESTS.DELETE.makeRequest(DiscordEndpoints.INVITE+inviteCode,
				new BasicNameValuePair("authorization", client.getToken()));
	}

	@Override
	public IDiscordClient getClient() {
		return client;
	}

	@Override
	public int hashCode() {
		return Objects.hash(inviteCode);
	}

	@Override
	public boolean equals(Object other) {
		if (other == null)
			return false;

		return this.getClass().isAssignableFrom(other.getClass()) && ((IInvite) other).getInviteCode().equals(getInviteCode());
	}

	@Override
	public String toString() {
		return inviteCode;
	}
}
