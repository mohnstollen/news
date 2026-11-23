package io.github.mohnstollen.news.playback;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import java.util.List;
import java.util.function.Supplier;

/**
 * Passes every player event on to [target], except that a change of the available commands
 * reports the commands of the player around (see QueuePlayer), not those of the wrapped one.
 * Written in Java on purpose: all listener methods are Java default methods, which Kotlin's
 * interface delegation does not pass on, so each one is forwarded by hand.
 */
@SuppressWarnings("deprecation")
final class CommandsListener implements Player.Listener {
    final Player.Listener target;
    private final Supplier<Player.Commands> commands;

    CommandsListener(Player.Listener target, Supplier<Player.Commands> commands) {
        this.target = target;
        this.commands = commands;
    }

    @Override
    public void onAvailableCommandsChanged(Player.Commands availableCommands) {
        target.onAvailableCommandsChanged(commands.get());
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        target.onEvents(player, events);
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        target.onTimelineChanged(timeline, reason);
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        target.onMediaItemTransition(mediaItem, reason);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        target.onTracksChanged(tracks);
    }

    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
        target.onMediaMetadataChanged(mediaMetadata);
    }

    @Override
    public void onPlaylistMetadataChanged(MediaMetadata mediaMetadata) {
        target.onPlaylistMetadataChanged(mediaMetadata);
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        target.onIsLoadingChanged(isLoading);
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        target.onLoadingChanged(isLoading);
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        target.onTrackSelectionParametersChanged(parameters);
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        target.onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        target.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        target.onPlayWhenReadyChanged(playWhenReady, reason);
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(int reason) {
        target.onPlaybackSuppressionReasonChanged(reason);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        target.onIsPlayingChanged(isPlaying);
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        target.onRepeatModeChanged(repeatMode);
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean enabled) {
        target.onShuffleModeEnabledChanged(enabled);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        target.onPlayerError(error);
    }

    @Override
    public void onPlayerErrorChanged(@Nullable PlaybackException error) {
        target.onPlayerErrorChanged(error);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        target.onPositionDiscontinuity(reason);
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        target.onPositionDiscontinuity(oldPosition, newPosition, reason);
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters parameters) {
        target.onPlaybackParametersChanged(parameters);
    }

    @Override
    public void onSeekBackIncrementChanged(long ms) {
        target.onSeekBackIncrementChanged(ms);
    }

    @Override
    public void onSeekForwardIncrementChanged(long ms) {
        target.onSeekForwardIncrementChanged(ms);
    }

    @Override
    public void onMaxSeekToPreviousPositionChanged(long ms) {
        target.onMaxSeekToPreviousPositionChanged(ms);
    }

    @Override
    public void onAudioSessionIdChanged(int id) {
        target.onAudioSessionIdChanged(id);
    }

    @Override
    public void onAudioAttributesChanged(AudioAttributes attributes) {
        target.onAudioAttributesChanged(attributes);
    }

    @Override
    public void onVolumeChanged(float volume) {
        target.onVolumeChanged(volume);
    }

    @Override
    public void onSkipSilenceEnabledChanged(boolean enabled) {
        target.onSkipSilenceEnabledChanged(enabled);
    }

    @Override
    public void onDeviceInfoChanged(DeviceInfo info) {
        target.onDeviceInfoChanged(info);
    }

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {
        target.onDeviceVolumeChanged(volume, muted);
    }

    @Override
    public void onVideoSizeChanged(VideoSize size) {
        target.onVideoSizeChanged(size);
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
        target.onSurfaceSizeChanged(width, height);
    }

    @Override
    public void onRenderedFirstFrame() {
        target.onRenderedFirstFrame();
    }

    @Override
    public void onCues(List<Cue> cues) {
        target.onCues(cues);
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        target.onCues(cueGroup);
    }

    @Override
    public void onMetadata(Metadata metadata) {
        target.onMetadata(metadata);
    }
}
