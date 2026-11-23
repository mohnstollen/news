package io.github.mohnstollen.news.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

/**
 * The buttons of the notification and the lock screen. Android puts two of them next to play/pause
 * and may show more beside them; some devices (e.g. Xiaomi) show only those two on the lock screen.
 * [seekMain] chooses which pair gets the places next to play/pause: false = previous/next
 * episode, true = back 10 s / forward 30 s (to skip ads). The other pair comes after.
 */
@OptIn(UnstableApi::class)
object LockScreenControls {
    val SEEK_BACK = SessionCommand("podfang.SEEK_BACK", Bundle.EMPTY)
    val SEEK_FORWARD = SessionCommand("podfang.SEEK_FORWARD", Bundle.EMPTY)
    val PREVIOUS_EPISODE = SessionCommand("podfang.PREVIOUS_EPISODE", Bundle.EMPTY)
    val NEXT_EPISODE = SessionCommand("podfang.NEXT_EPISODE", Bundle.EMPTY)

    /** Our own commands come on top of the usual ones, for every controller. */
    val sessionCommands: SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        .add(SEEK_BACK).add(SEEK_FORWARD).add(PREVIOUS_EPISODE).add(NEXT_EPISODE)
        .build()

    private val episodeCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )

    /**
     * Player commands of the notification (which the lock screen follows). With [seekMain], the
     * standard previous/next are hidden there, so the 10 s / 30 s buttons take their places.
     */
    fun notificationPlayerCommands(seekMain: Boolean): Player.Commands {
        val all = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        return if (seekMain) all.buildUpon().removeAll(*episodeCommands).build() else all
    }

    private fun button(icon: Int, command: SessionCommand, name: String, slot: Int) =
        CommandButton.Builder(icon).setSessionCommand(command).setDisplayName(name).setSlots(slot).build()

    fun buttons(seekMain: Boolean): List<CommandButton> {
        val back = "10 Sekunden zurück"
        val forward = "30 Sekunden vor"
        return if (seekMain) {
            listOf(
                button(CommandButton.ICON_SKIP_BACK_10, SEEK_BACK, back, CommandButton.SLOT_BACK),
                button(CommandButton.ICON_SKIP_FORWARD_30, SEEK_FORWARD, forward, CommandButton.SLOT_FORWARD),
                button(CommandButton.ICON_NEXT, NEXT_EPISODE, "Nächste Folge", CommandButton.SLOT_OVERFLOW),
                button(CommandButton.ICON_PREVIOUS, PREVIOUS_EPISODE, "Vorherige Folge", CommandButton.SLOT_OVERFLOW),
            )
        } else {
            // Previous/next take their places through the player commands; 30 s first, it skips ads.
            listOf(
                button(CommandButton.ICON_SKIP_FORWARD_30, SEEK_FORWARD, forward, CommandButton.SLOT_OVERFLOW),
                button(CommandButton.ICON_SKIP_BACK_10, SEEK_BACK, back, CommandButton.SLOT_OVERFLOW),
            )
        }
    }
}

/**
 * The player as the system sees it: "next" and "previous" always work and mean episodes, taken
 * from the queue and from what was played before, though the player itself holds one episode.
 */
@OptIn(UnstableApi::class)
class QueuePlayer(
    player: Player,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) : ForwardingPlayer(player) {
    private val added = intArrayOf(
        COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*added).build()

    override fun isCommandAvailable(command: Int): Boolean = command in added || super.isCommandAvailable(command)

    // A change of the wrapped player's commands is passed on with ours added, or they get lost.
    private val wrappers = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapper = CommandsListener(listener) { availableCommands }
        wrappers[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrappers.remove(listener) ?: listener)
    }

    override fun seekToNext() = onNext()
    override fun seekToNextMediaItem() = onNext()
    override fun seekToPrevious() = onPrevious()
    override fun seekToPreviousMediaItem() = onPrevious()
}
