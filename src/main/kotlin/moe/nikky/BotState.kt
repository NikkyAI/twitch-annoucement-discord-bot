package moe.nikky

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.channel.GuildMessageChannelBehavior
import dev.kord.core.behavior.channel.TextChannelBehavior
import dev.kord.core.behavior.getChannelOf
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.Role
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.firstOrNull
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class BotState(
    val botname: String,
    val guildBehavior: GuildBehavior,
    val adminRole: Role? = null,
    val roleChooser: Map<String, RolePickerMessageState> = emptyMap(),
    val twitchNotifications: Map<String, TwitchNotificationState> = emptyMap(),
    val twitchBackgroundJob: Job? = null,
) {
    fun asSerialized() = ConfigurationStateSerialized(
        adminRole = adminRole?.id,
        roleChooser = roleChooser.mapValues { (section, rolePickerMessageState) ->
            rolePickerMessageState.asSerialized()
        },
        twitchNotifications = twitchNotifications.mapValues { (_, value) ->
            value.asSerialized()
        }
    )
}


data class TwitchNotificationState(
    val twitchUserName: String,
    val channel: TextChannelBehavior,
    val role: Role,
    val oldMessage: MessageBehavior? = null,
) {
    fun asSerialized(): TwitchNotificationConfig {
        return TwitchNotificationConfig(
            twitchUserName = twitchUserName,
            channel = channel.id,
            message = oldMessage?.id,
            role = role.id
        )
    }
}

data class RolePickerMessageState(
    val channel: GuildMessageChannelBehavior,
    val message: MessageBehavior,
    val roleMapping: Map<ReactionEmoji, Role> = emptyMap(),
    val liveMessageJob: Job = Job(),
) {
    fun asSerialized(): RolePickerMessageConfig {
        return RolePickerMessageConfig(
            channel = channel.id,
            message = message.id,
            roleMapping = roleMapping.entries.associate { (reactionEmoji, role) ->
                reactionEmoji.mention to role.id
            }
        )
    }
}

@Serializable
data class RolePickerMessageConfig(
    val channel: Snowflake,
    val message: Snowflake,
    val roleMapping: Map<String, Snowflake>,
) {
    suspend fun resolve(guildBehavior: GuildBehavior): RolePickerMessageState {
        val channel: TextChannelBehavior = guildBehavior.getChannelOf<TextChannel>(channel)
        return RolePickerMessageState(
            channel = channel,
            message = channel.getMessage(message),
            roleMapping = roleMapping.entries.associate { (reactionEmojiName, role) ->
                val reactionEmoji = guildBehavior.emojis.firstOrNull { it.mention == reactionEmojiName }
                    ?.let { ReactionEmoji.from(it) }
                    ?: ReactionEmoji.Unicode(reactionEmojiName)
                reactionEmoji to guildBehavior.getRole(role)
            }
        )
    }
}

@Serializable
data class TwitchNotificationConfig(
    val twitchUserName: String,
    val channel: Snowflake,
    val message: Snowflake?,
    val role: Snowflake,
) {
    suspend fun resolve(guildBehavior: GuildBehavior): TwitchNotificationState {
        val channel = guildBehavior.getChannelOf<TextChannel>(channel)
        return TwitchNotificationState(
            channel = channel,
            twitchUserName = twitchUserName,
            oldMessage = message?.let {
                channel.getMessageOrNull(it)
            },
            role = guildBehavior.getRole(role)
        )
    }
}

@Serializable
data class ConfigurationStateSerialized(
    val adminRole: Snowflake? = null,
    val roleChooser: Map<String, RolePickerMessageConfig> = emptyMap(),
    val twitchNotifications: Map<String, TwitchNotificationConfig> = emptyMap(),
) {
    suspend fun resolve(guildBehavior: GuildBehavior): BotState {
        val kord = guildBehavior.kord
        val botname = guildBehavior.getMember(kord.selfId).displayName

        return BotState(
            botname = botname,
            guildBehavior = guildBehavior,
            adminRole = adminRole?.let { guildBehavior.getRoleOrNull(it) },
            roleChooser = roleChooser.mapValues { (section, rolePickerConfig) ->
                rolePickerConfig.resolve(guildBehavior)
            },
            twitchNotifications = twitchNotifications.mapValues { (_, value) ->
                value.resolve(guildBehavior)
            }
        )
    }
}
