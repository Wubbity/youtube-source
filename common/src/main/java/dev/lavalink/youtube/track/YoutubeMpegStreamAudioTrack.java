package dev.lavalink.youtube.track;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegFileLoader;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;
import static com.sedmelluq.discord.lavaplayer.tools.Units.CONTENT_LENGTH_UNKNOWN;

/**
 * YouTube segmented MPEG stream track. The base URL always gives the latest chunk. Every chunk contains the current
 * sequence number in it, which is used to get the sequence number of the next segment. This is repeated until YouTube
 * responds to a segment request with 204.
 */
public class YoutubeMpegStreamAudioTrack extends MpegAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(YoutubeMpegStreamAudioTrack.class);
    private static final RequestConfig streamingRequestConfig = RequestConfig.custom()
        .setSocketTimeout(3000)
        .setConnectionRequestTimeout(3000)
        .setConnectTimeout(3000)
        .build();
    private static final long EMPTY_RETRY_THRESHOLD_MS = 400;
    private static final long EMPTY_RETRY_INTERVAL_MS = 50;
    // Live broadcasts get a longer budget than the 400ms above, which is
    // shorter than a single socket timeout (3000ms) on the same request
    // config and so gives up on a still-running broadcast after one slow
    // fetch.
    //
    // 5000ms is chosen against frameBufferDurationMs, which is also 5000:
    // a stall the retry recovers from inside that window is covered by
    // buffered audio and is inaudible. Anything longer is silence either
    // way, so it is better to end and let the client re-resolve than to
    // keep waiting.
    //
    // Do NOT raise this much further. The retry loop runs on the track's
    // processing thread and produces no frames while it spins, so a long
    // budget just means Lavalink's stuck detector fires instead, AFTER the
    // buffer drains. Measured: at 30000ms the cuts came back as
    // reason=stuck with a noticeably LONGER gap than the original 400ms.
    private static final long LIVE_EMPTY_RETRY_THRESHOLD_MS = 5000;
    private static final long LIVE_EMPTY_RETRY_INTERVAL_MS = 250;
    private static final long MAX_REWIND_TIME = 43200; // Seconds

    private final HttpInterface httpInterface;
    private final TrackState state;

    /**
     * @param trackInfo Track info
     * @param httpInterface HTTP interface to use for loading segments
     * @param signedUrl URI of the base stream with signature resolved
     */
    public YoutubeMpegStreamAudioTrack(AudioTrackInfo trackInfo,
                                       HttpInterface httpInterface,
                                       URI signedUrl) {
        super(trackInfo, null);

        this.httpInterface = httpInterface;
        this.state = new TrackState(signedUrl);

        // YouTube does not return a segment until it is ready, this might trigger a connect timeout otherwise.
        httpInterface.getContext().setRequestConfig(streamingRequestConfig);
        updateGlobalSequence();
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) {
        localExecutor.executeProcessingLoop(() -> execute(localExecutor), this::seek);
    }

    @Override
    public void setPosition(long position) {
        state.seeking = true;
        updateGlobalSequence();
        getActiveExecutor().setPosition(position);
    }

    @Override
    public long getDuration() {
        return TimeUnit.SECONDS.toMillis(state.globalSequence * TimeUnit.MILLISECONDS.toSeconds(state.globalSequenceDuration));
    }

    @Override
    public long getPosition() {
        if (state.absoluteSequence == null) {
            return super.getPosition();
        }

        return TimeUnit.SECONDS.toMillis(state.absoluteSequence * TimeUnit.MILLISECONDS.toSeconds(state.globalSequenceDuration));
    }

    private void updateGlobalSequence() {
        URI urlToFetch = state.initialUrl;

        if (trackInfo.isStream) {
            // Live broadcast URLs (noclen=1, live=1) reject HTTP range requests with 400.
            // YoutubePersistentHttpStream adds &range=0-N whenever the URL lacks "rn=".
            // Adding rn=0 here causes getConnectUrl() to return the URL as-is, bypassing
            // the range logic so we can fetch the current live segment without a 400.
            try {
                urlToFetch = new URIBuilder(state.initialUrl).setParameter("rn", "0").build();
            } catch (URISyntaxException e) {
                return;
            }
        }

        try (YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(httpInterface, urlToFetch, CONTENT_LENGTH_UNKNOWN)) {
            MpegFileLoader file = new MpegFileLoader(stream);
            file.parseHeaders();

            SequenceInfo sequenceInfo = extractAbsoluteSequenceFromEvent(file.getLastEventMessage());

            if (sequenceInfo != null) {
                state.globalSequence = sequenceInfo.sequence;
                state.globalSequenceDuration = sequenceInfo.duration;
            }
        } catch (IOException ignored) {

        }
    }

    private void execute(LocalAudioTrackExecutor localExecutor) throws InterruptedException {
        if (!trackInfo.isStream && state.absoluteSequence == null) {
            state.absoluteSequence = 0L;
        }

        if (trackInfo.isStream) {
            log.info("Starting livestream playback: {} / {}", trackInfo.title, trackInfo.author);
        }

        try {
            while (!state.finished) {
                processNextSegmentWithRetry(localExecutor);
                state.relativeSequence++;
                state.globalSequence++;
            }
        } finally {
            if (state.trackConsumer != null && !state.seeking) {
                state.trackConsumer.close();
            } else {
                state.seeking = false;
            }
        }
    }

    private void seek(long timecode) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(timecode);

        if (seconds > state.globalSequence) {
            seconds = state.globalSequence;
        } else if (state.globalSequence - seconds > MAX_REWIND_TIME) {
            seconds = state.globalSequence - MAX_REWIND_TIME;
        }

        state.absoluteSequence = seconds - 1;
    }

    private void processNextSegmentWithRetry(
        LocalAudioTrackExecutor localExecutor
    ) throws InterruptedException {
        if (processNextSegment(localExecutor)) {
            return;
        }

        // First attempt gave empty result, possibly because the stream is not yet finished, but the next segment is just
        // not ready yet. Keep retrying at the interval below until the threshold below is reached; both are much
        // more generous for a live broadcast, where a late segment is routine rather than the end of the stream.
        long threshold = trackInfo.isStream ? LIVE_EMPTY_RETRY_THRESHOLD_MS : EMPTY_RETRY_THRESHOLD_MS;
        long interval = trackInfo.isStream ? LIVE_EMPTY_RETRY_INTERVAL_MS : EMPTY_RETRY_INTERVAL_MS;

        long waitStart = System.currentTimeMillis();
        long iterationStart = waitStart;

        while (!processNextSegment(localExecutor)) {
            // The threshold is the maximum time between the end of the first attempt and the beginning of the last
            // attempt, to avoid retry being skipped due to response coming slowly.
            if (iterationStart - waitStart >= threshold) {
                if (trackInfo.isStream) {
                    log.info("Live segment supply did not recover within {}ms, ending stream: {}", threshold, trackInfo.title);
                }

                state.finished = true;
                break;
            } else {
                Thread.sleep(interval);
                iterationStart = System.currentTimeMillis();
            }
        }
    }

    private boolean processNextSegment(
        LocalAudioTrackExecutor localExecutor
    ) throws InterruptedException {
        URI segmentUrl = getNextSegmentUrl(state);

        try (YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(httpInterface, segmentUrl, CONTENT_LENGTH_UNKNOWN)) {
            if (stream.checkStatusCode() == HttpStatus.SC_NO_CONTENT || stream.getContentLength() == 0) {
                discardRedirect();
                return false;
            }

            // If we were redirected, use that URL as a base for the next segment URL. Otherwise we will likely get redirected
            // again on every other request, which is inefficient (redirects across domains, the original URL is always
            // closing the connection, whereas the final URL is keep-alive).
            state.redirectUrl = httpInterface.getFinalLocation();

            processSegmentStream(stream, localExecutor.getProcessingContext(), state);

            stream.releaseConnection();
        } catch (IOException e) {
            // IOException here usually means that stream is about to end.
            discardRedirect();
            return false;
        }

        return true;
    }

    private void processSegmentStream(SeekableInputStream stream, AudioProcessingContext context, TrackState state) throws InterruptedException, IOException {
        MpegFileLoader file = new MpegFileLoader(stream);
        file.parseHeaders();

        if (!trackInfo.isStream) {
            state.absoluteSequence++;
        } else {
            SequenceInfo sequenceInfo = extractAbsoluteSequenceFromEvent(file.getLastEventMessage());

            if (sequenceInfo != null) {
                state.absoluteSequence = sequenceInfo.sequence;
            }
        }

        if (state.trackConsumer == null) {
            state.trackConsumer = loadAudioTrack(file, context);
        }

        MpegFileTrackProvider fileReader = file.loadReader(state.trackConsumer);
        if (fileReader == null) {
            throw new FriendlyException("Unknown MP4 format.", SUSPICIOUS, null);
        }

        fileReader.provideFrames();
    }

    /**
     * Forget the cached redirect target after a failed segment fetch, so the next
     * attempt starts from the original signed URL again.
     *
     * Caching the redirect is purely an efficiency measure (see processNextSegment):
     * it keeps segments on one keep-alive CDN host instead of re-redirecting every
     * other request. But it is cached for the life of the track, so once that host
     * stops serving us - node expiry, or an egress IP change under a URL that is
     * pinned to the address that signed it, which is what a rotating residential
     * proxy does - every later segment fetches the same dead endpoint and no amount
     * of retrying can recover. Falling back to the initial URL costs one redirect
     * and lets YouTube hand out a live host.
     */
    private void discardRedirect() {
        state.redirectUrl = null;
    }

    private URI getNextSegmentUrl(TrackState state) {
        URIBuilder builder = new URIBuilder(state.redirectUrl == null ? state.initialUrl : state.redirectUrl)
            .setParameter("rn", String.valueOf(state.relativeSequence))
            .setParameter("rbuf", "0");

        if (state.absoluteSequence != null) {
            builder.setParameter("sq", String.valueOf(state.absoluteSequence + 1));
        }

        try {
            return builder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private SequenceInfo extractAbsoluteSequenceFromEvent(byte[] data) {
        if (data == null) {
            return null;
        }

        String message = new String(data, StandardCharsets.UTF_8);
        String sequence = DataFormatTools.extractBetween(message, "Sequence-Number: ", "\r\n");
        String duration = DataFormatTools.extractBetween(message, "Target-Duration-Us: ", "\r\n");

        if (sequence != null && duration != null) {
            return new SequenceInfo(Long.parseLong(sequence), TimeUnit.MICROSECONDS.toMillis(Long.parseLong(duration)));
        }

        return null;
    }

    private static class TrackState {
        private long globalSequenceDuration;
        private long globalSequence;
        private long relativeSequence;
        private Long absoluteSequence;
        private MpegTrackConsumer trackConsumer;
        private boolean finished;
        private boolean seeking;
        private URI redirectUrl;
        private final URI initialUrl;

        public TrackState(URI initialUrl) {
            this.initialUrl = initialUrl;
        }
    }

    private static class SequenceInfo {
        private final long sequence;
        private final long duration;

        public SequenceInfo(long sequence, long duration) {
            this.sequence = sequence;
            this.duration = duration;
        }
    }
}
