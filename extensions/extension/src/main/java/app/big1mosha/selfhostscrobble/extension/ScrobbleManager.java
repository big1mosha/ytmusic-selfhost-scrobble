/*
 * Personal use only. See the README for details.
 *
 * Based on the ScrobbleManager of the Morphe scrobbling patch (GPLv3).
 */

package app.big1mosha.selfhostscrobble.extension;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

/**
 * Watches the media session and reports tracks to the configured server.
 *
 * <p>All methods must be called on the main thread. Network work is handed to
 * {@link ListenBrainzClient}, which runs it on its own worker thread.
 */
final class ScrobbleManager {
    private static final String TAG = "SelfHostScrobble";

    private static ScrobbleManager instance;

    static ScrobbleManager getInstance() {
        if (instance == null) {
            instance = new ScrobbleManager();
        }
        return instance;
    }

    /**
     * The track that is currently playing.
     *
     * <p>Metadata of the next track can never reach a scrobble that was scheduled
     * for this one, because the scrobble reads this snapshot instead of the live
     * player state.
     */
    private static final class Track {
        final String title;
        final String artist;
        final String key;

        String mediaId;
        String album;
        int durationSeconds;
        long startedAtSeconds;

        Track(String mediaId, String title, String artist, String album, int durationSeconds) {
            this.mediaId = mediaId == null ? "" : mediaId.trim();
            this.title = title;
            this.artist = artist;
            this.album = isEmpty(album) ? null : album;
            this.durationSeconds = durationSeconds;
            this.key = this.mediaId.isEmpty() ? title + '\u0000' + artist : "id:" + this.mediaId;
        }

        /** Same song, unless both sides carry a media id and the ids differ. */
        boolean isSameTrack(Track other) {
            if (!title.equals(other.title) || !artist.equals(other.artist)) {
                return false;
            }
            return mediaId.isEmpty() || other.mediaId.isEmpty() || mediaId.equals(other.mediaId);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Track current;

    private boolean songStarted;
    private boolean isPlayerPlaying;

    private long scrobbleRemainingMillis;
    private long scrobbleTimerStartedAt;
    private boolean scrobbled;
    private Runnable scrobbleRunnable;

    private String lastScrobbledKey;
    private long lastScrobbledAt;

    private ScrobbleManager() {
    }

    void onSetMetadata(MediaMetadata metadata) {
        if (metadata == null || !Config.isConfigured()) {
            return;
        }

        try {
            String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            String album = cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);

            Pair<String, String> resolved = resolveTitleAndArtist(rawTitle, rawArtist);
            String title = resolved.first;
            String artist = resolved.second;
            int duration = (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);

            if (isEmpty(title) || isEmpty(artist)) {
                return;
            }

            Track candidate = new Track(mediaId, title, artist, album, duration);

            if (current == null || !current.isSameTrack(candidate)) {
                Log.d(TAG, "New track: " + title + " - " + artist
                        + " [album=" + album + ", id=" + mediaId + "]");
                stopTimer();
                songStarted = false;
                scrobbled = false;
                current = candidate;

                if (isPlayerPlaying) {
                    onSongStart();
                }
                return;
            }

            boolean updated = false;

            if (candidate.durationSeconds > current.durationSeconds) {
                current.durationSeconds = candidate.durationSeconds;
                updated = true;
            }
            if (current.mediaId.isEmpty() && !candidate.mediaId.isEmpty()) {
                current.mediaId = candidate.mediaId;
                updated = true;
            }
            // Only fill a missing album. Never replace one, so a late update for
            // another track cannot relabel this one.
            if (current.album == null && candidate.album != null) {
                current.album = candidate.album;
                updated = true;
                Log.d(TAG, "Album for '" + current.title + "': " + current.album);
            }

            if (updated && songStarted && isPlayerPlaying) {
                // Metadata arrived after playback started, so the timer may have been skipped.
                if (!scrobbled && scrobbleRunnable == null && scrobbleTimerStartedAt == 0L) {
                    startTimer();
                }
                if (Config.nowPlaying()) {
                    nowPlaying();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onSetMetadata failed", t);
        }
    }

    void onSetPlaybackState(PlaybackState state) {
        if (state == null) {
            return;
        }

        isPlayerPlaying = state.getState() == PlaybackState.STATE_PLAYING;

        if (current == null) {
            return;
        }

        if (isPlayerPlaying) {
            if (!songStarted) {
                onSongStart();
            } else {
                onSongResume();
            }
        } else {
            onSongPause();
        }
    }

    private void onSongStart() {
        if (current == null) {
            return;
        }

        current.startedAtSeconds = System.currentTimeMillis() / 1000;
        songStarted = true;

        startTimer();

        if (Config.nowPlaying()) {
            nowPlaying();
        }
    }

    private void onSongResume() {
        if (!scrobbled && scrobbleRemainingMillis > 0) {
            cancelRunnable();
            scrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleScrobble(scrobbleRemainingMillis);
        }
    }

    private void onSongPause() {
        if (!songStarted) {
            return;
        }

        cancelRunnable();
        if (scrobbleTimerStartedAt != 0L) {
            long elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt;
            scrobbleRemainingMillis = Math.max(0, scrobbleRemainingMillis - elapsed);
            scrobbleTimerStartedAt = 0L;
        }
    }

    private void startTimer() {
        cancelRunnable();

        Track track = current;
        if (track == null) {
            return;
        }

        if (track.durationSeconds <= Config.MIN_SONG_DURATION_SECONDS) {
            Log.d(TAG, "Skipping short track (" + track.durationSeconds + "s)");
            return;
        }

        long thresholdMillis = (long) (track.durationSeconds * 1000L * (Config.DELAY_PERCENT / 100.0f));
        long totalDelayMillis = Math.min(thresholdMillis, Config.DELAY_SECONDS * 1000L);
        long elapsedMillis = Math.max(
                0,
                System.currentTimeMillis() - (track.startedAtSeconds * 1000L)
        );

        scrobbleRemainingMillis = totalDelayMillis - elapsedMillis;

        if (scrobbleRemainingMillis <= 0) {
            scrobble(track);
            return;
        }

        if (isPlayerPlaying) {
            scrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleScrobble(scrobbleRemainingMillis);
        } else {
            scrobbleTimerStartedAt = 0L;
        }
    }

    private void stopTimer() {
        cancelRunnable();
        scrobbleRemainingMillis = 0L;
        scrobbleTimerStartedAt = 0L;
    }

    private void scheduleScrobble(long delayMillis) {
        final Track track = current;
        scrobbleRunnable = () -> {
            scrobble(track);
            scrobbleRunnable = null;
        };
        handler.postDelayed(scrobbleRunnable, delayMillis);
    }

    private void cancelRunnable() {
        if (scrobbleRunnable != null) {
            handler.removeCallbacks(scrobbleRunnable);
            scrobbleRunnable = null;
        }
    }

    private void nowPlaying() {
        Track track = current;
        if (track == null) {
            return;
        }

        ListenBrainzClient.nowPlaying(
                track.artist, track.title, track.album, track.mediaId, track.durationSeconds
        );
    }

    private void scrobble(Track track) {
        if (scrobbled || track == null || track != current) {
            return;
        }
        scrobbled = true;

        long now = System.currentTimeMillis();
        if (track.key.equals(lastScrobbledKey)
                && now - lastScrobbledAt < Config.DEDUPE_SECONDS * 1000L) {
            Log.d(TAG, "Skipping duplicate scrobble of '" + track.title + "'");
            return;
        }
        lastScrobbledKey = track.key;
        lastScrobbledAt = now;

        Log.d(TAG, "Scrobbling '" + track.title + "' by " + track.artist
                + " [album=" + track.album + ", id=" + track.mediaId + "]");
        ListenBrainzClient.scrobble(
                track.artist, track.title, track.album, track.mediaId,
                track.durationSeconds, track.startedAtSeconds
        );
    }

    private String cleanTitle(String title) {
        if (title == null) {
            return null;
        }

        String clean = title;
        if (Config.CLEAN_METADATA) {
            clean = clean.replaceAll("(?i)\\s*[（(\\[](official\\s+)?(video|audio|music\\s+video|lyric\\s+video|visualizer)[）)\\]]", "");
            clean = clean.replaceAll("(?i)\\s*[（(\\[](\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?[）)\\]]", "");
            clean = clean.replaceAll("(?i)\\s*[（(\\[]live(\\s+at\\s+.*|\\s+\\d{4})?[）)\\]]", "");
            clean = clean.replaceAll("(?i)\\s*[（(\\[](mono|stereo|hq|hd)[）)\\]]", "");
            clean = applyCustomRegex(clean);
        }
        return clean.replaceAll("\\s+", " ").trim();
    }

    private String cleanArtist(String artist) {
        if (artist == null) {
            return null;
        }

        String clean = artist;
        if (Config.CLEAN_METADATA) {
            clean = clean.replaceAll("(?i)\\s*-\\s*topic$", "");
            clean = applyCustomRegex(clean);
        }
        return clean.replaceAll("\\s+", " ").trim();
    }

    private String cleanAlbum(String album) {
        if (album == null) {
            return null;
        }

        String clean = album;
        if (Config.CLEAN_METADATA) {
            clean = clean.replaceAll("(?i)\\s*[（(\\[](\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?[）)\\]]", "");
            clean = applyCustomRegex(clean);
        }
        return clean.replaceAll("\\s+", " ").trim();
    }

    private Pair<String, String> resolveTitleAndArtist(String rawTitle, String rawArtist) {
        String title = cleanTitle(rawTitle);
        String artist = cleanArtist(rawArtist);

        if (Config.PARSE_TITLE && rawTitle != null) {
            String separator = " - ";
            int separatorIndex = rawTitle.indexOf(separator);
            if (separatorIndex > 0 && separatorIndex < rawTitle.length() - separator.length()) {
                String parsedArtist = cleanArtist(rawTitle.substring(0, separatorIndex).trim());
                String parsedTrack = cleanTitle(rawTitle.substring(separatorIndex + separator.length()).trim());
                if (!isEmpty(parsedArtist) && !isEmpty(parsedTrack)) {
                    title = parsedTrack;
                    artist = parsedArtist;
                }
            }
        }

        return new Pair<>(title, artist);
    }

    private String applyCustomRegex(String input) {
        String regex = Config.CUSTOM_REGEX;
        if (isEmpty(regex)) {
            return input;
        }

        try {
            return input.replaceAll(regex, "");
        } catch (Exception ex) {
            Log.w(TAG, "Invalid custom regex: " + regex, ex);
            return input;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
