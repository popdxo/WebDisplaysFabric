package net.montoyo.wd.client.mcef;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import net.montoyo.wd.utilities.Log;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one owner MCEF browser as a real WebRTC video track. Pixels from {@code MCEFBrowser.onPaint} are
 * converted to I420 and pushed into a {@link CustomVideoSource}; every viewer gets its own peer connection that
 * is negotiated through signaling messages relayed over the Minecraft connection (viewer sends {@code join}, we answer with an
 * {@code offer}, the viewer returns an {@code answer}). Encoding is done by libwebrtc (VP8/VP9/H264).
 */
final class WebRtcOwnerStream implements AutoCloseable {
    private static final int MAX_WIDTH = Integer.getInteger("webdisplays.stream.maxWidth", 1280);
    private static final int MAX_HEIGHT = Integer.getInteger("webdisplays.stream.maxHeight", 720);
    private static final int MAX_BITRATE = Integer.getInteger("webdisplays.stream.maxBitrate", 6_000_000);
    private static final long MIN_FRAME_INTERVAL_NANOS = 12_000_000L; // < 1/60 s so jittery 60 Hz paints are not aliased down to 30
    private static final long KEEPALIVE_NANOS = 500_000_000L;
    private static final int MAX_PEERS = 16;
    /** Empty disables STUN (LAN/localhost only). Viewers on other networks need a reachable STUN server. */
    private static final String STUN_URL = System.getProperty("webdisplays.stream.stun", "stun:stun.l.google.com:19302");

    private static PeerConnectionFactory factory;
    private static boolean factoryFailed;

    private final String sessionId;
    private final HybridFrameCapture.SignalSender sender;
    private final CustomVideoSource source = new CustomVideoSource();
    private final VideoTrack track;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newCachedThreadPool(daemon("WebDisplays-WebRTC-Worker"));
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(daemon("WebDisplays-WebRTC-KeepAlive"));
    private final Object frameLock = new Object();
    private volatile boolean running = true;
    private volatile Runnable repaintHook;
    private VideoFrameBuffer lastBuffer;
    private long lastPushNanos;
    private long lastRepeatNanos;

    private WebRtcOwnerStream(String sessionId, HybridFrameCapture.SignalSender sender) {
        this.sessionId = sessionId;
        this.sender = sender;
        this.track = factory().createVideoTrack("webdisplays-" + sessionId, source);
    }

    /** Returns null when the WebRTC native library cannot be loaded (caller falls back to the JPEG relay). */
    static WebRtcOwnerStream create(String sessionId, HybridFrameCapture.SignalSender sender) {
        try {
            if (factory() == null) return null;
            WebRtcOwnerStream stream = new WebRtcOwnerStream(sessionId, sender);
            stream.start();
            return stream;
        } catch (Throwable error) {
            Log.warningEx("WebRTC streaming unavailable, falling back to JPEG relay", error);
            return null;
        }
    }

    void setRepaintHook(Runnable hook) {
        this.repaintHook = hook;
    }

    private void requestRepaint() {
        Runnable hook = repaintHook;
        if (hook != null) hook.run();
    }

    boolean matches(String sessionId) {
        return this.sessionId.equals(sessionId);
    }

    private static synchronized PeerConnectionFactory factory() {
        if (factory == null && !factoryFailed) {
            try {
                // libwebrtc initializes COM (MTA) on the constructing thread; the render thread is already an STA
                // (CEF), which aborts the JVM natively. Build the factory on a clean thread with a dummy audio
                // device, since no audio is streamed.
                PeerConnectionFactory[] holder = new PeerConnectionFactory[1];
                Throwable[] failure = new Throwable[1];
                Thread builder = new Thread(() -> {
                    try {
                        holder[0] = new PeerConnectionFactory(new AudioDeviceModule(AudioLayer.kDummyAudio));
                    } catch (Throwable error) {
                        failure[0] = error;
                    }
                }, "WebDisplays-WebRTC-Init");
                builder.setDaemon(true);
                builder.start();
                builder.join();
                if (failure[0] != null) throw failure[0];
                factory = holder[0];
            } catch (Throwable error) {
                factoryFailed = true;
                Log.warningEx("Could not load webrtc-java native library", error);
            }
        }
        return factory;
    }

    private void start() {
        // CEF only paints on change; repeat the last frame so new viewers and the encoder always get pixels.
        keepAlive.scheduleWithFixedDelay(this::repeatLastFrame, 250, 250, TimeUnit.MILLISECONDS);
        Log.info("WebRTC stream started for session {}", sessionId);
    }

    // ---- video path -------------------------------------------------------------------------------------

    /** Called on the CEF paint thread with a BGRA buffer valid only for the duration of the call. */
    void pushPaint(ByteBuffer pixels, int width, int height) {
        long now = System.nanoTime();
        synchronized (frameLock) {
            if (!running || now - lastPushNanos < MIN_FRAME_INTERVAL_NANOS) return;
            // Nobody is watching: still refresh the cached frame once a second so a new viewer has something.
            if (peers.isEmpty() && lastBuffer != null && now - lastPushNanos < 1_000_000_000L) return;
            lastPushNanos = now;
        }
        NativeI420Buffer i420 = null;
        VideoFrameBuffer out = null;
        try {
            i420 = NativeI420Buffer.allocate(width, height);
            ByteBuffer src = pixels.slice();
            src.limit(width * height * 4);
            VideoBufferConverter.convertToI420(src, i420, FourCC.ARGB); // libyuv ARGB == BGRA byte order
            out = i420;
            double scale = Math.min(1.0, Math.min(MAX_WIDTH / (double) width, MAX_HEIGHT / (double) height));
            if (scale < 1.0) {
                int ow = Math.max(2, ((int) (width * scale)) & ~1);
                int oh = Math.max(2, ((int) (height * scale)) & ~1);
                out = i420.cropAndScale(0, 0, width, height, ow, oh);
                i420.release();
                i420 = null;
            }
            submit(out, now);
            out = null; // ownership moved to lastBuffer
            i420 = null;
        } catch (Throwable error) {
            Log.warning("WebRTC frame push failed: {}", error.toString());
        } finally {
            if (out != null) out.release();
            else if (i420 != null) i420.release();
        }
    }

    /** Pushes {@code buffer} to the encoder and keeps it (taking over the caller's reference) as the repeat frame. */
    private void submit(VideoFrameBuffer buffer, long timestampNanos) {
        synchronized (frameLock) {
            if (!running) {
                buffer.release();
                return;
            }
            pushToSource(buffer, timestampNanos);
            if (lastBuffer != null) lastBuffer.release();
            lastBuffer = buffer;
            lastRepeatNanos = timestampNanos;
        }
    }

    private void pushToSource(VideoFrameBuffer buffer, long timestampNanos) {
        buffer.retain(); // the VideoFrame owns one reference and drops it in release()
        VideoFrame frame = new VideoFrame(buffer, timestampNanos);
        try {
            source.pushFrame(frame);
        } finally {
            frame.release();
        }
    }

    private void repeatLastFrame() {
        synchronized (frameLock) {
            long now = System.nanoTime();
            if (!running || lastBuffer == null || peers.isEmpty() || now - lastRepeatNanos < KEEPALIVE_NANOS) return;
            lastRepeatNanos = now;
            try {
                pushToSource(lastBuffer, now);
            } catch (Throwable error) {
                Log.warning("WebRTC keepalive push failed: {}", error.toString());
            }
        }
    }

    // ---- signaling (messages travel over the Minecraft connection; see HybridSignalRelay) --------------------

    /** A message from a viewer, relayed by the server: {"type":"join"} or {"type":"answer","sdp":...}. */
    void onSignal(String peerId, String json) {
        try {
            JsonObject message = JsonParser.parseString(json).getAsJsonObject();
            String type = message.get("type").getAsString();
            if ("join".equals(type)) {
                worker.execute(() -> openPeer(peerId));
            } else if ("answer".equals(type)) {
                Peer peer = peers.get(peerId);
                if (peer != null) peer.acceptAnswer(message.get("sdp").getAsString());
            }
        } catch (Exception error) {
            Log.warning("Ignoring bad WebRTC signal from {}: {}", peerId, error.toString());
        }
    }

    private void openPeer(String peerId) {
        if (!running) return;
        Peer existing = peers.get(peerId);
        if (existing != null) {
            // Viewers repeat `join` until they see an offer; answer duplicates idempotently.
            existing.resendOfferIfPending();
            return;
        }
        if (peers.size() >= MAX_PEERS) {
            Log.warning("WebRTC viewer limit ({}) reached, ignoring {}", MAX_PEERS, peerId);
            return;
        }
        try {
            Peer peer = new Peer(peerId);
            peers.put(peerId, peer);
            peer.offer();
            requestRepaint();
        } catch (Throwable error) {
            Log.warningEx("Could not create WebRTC peer " + peerId, error);
        }
    }

    private final class Peer {
        private final String peerId;
        private final CountDownLatch gathered = new CountDownLatch(1);
        private final RTCPeerConnection connection;
        private volatile boolean closed;
        private volatile boolean answered;
        private volatile String offerJson;

        Peer(String peerId) {
            this.peerId = peerId;
            RTCConfiguration configuration = new RTCConfiguration();
            if (!STUN_URL.isBlank()) {
                RTCIceServer stun = new RTCIceServer();
                stun.urls = List.of(STUN_URL);
                configuration.iceServers = List.of(stun);
            }
            connection = factory().createPeerConnection(configuration, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    // Non-trickle: candidates are bundled into the offer once gathering completes.
                }

                @Override
                public void onIceGatheringChange(RTCIceGatheringState state) {
                    if (state == RTCIceGatheringState.COMPLETE) gathered.countDown();
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    Log.info("WebRTC viewer {}: {}", peerId, state);
                    if (state == RTCPeerConnectionState.CONNECTED) {
                        synchronized (frameLock) {
                            lastRepeatNanos = 0; // make sure the fresh viewer gets a frame right away
                        }
                        requestRepaint();
                    } else if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED
                            || state == RTCPeerConnectionState.DISCONNECTED) {
                        try {
                            worker.execute(() -> {
                                if (peers.remove(peerId, Peer.this)) close();
                            });
                        } catch (java.util.concurrent.RejectedExecutionException ignored) {
                            // stream already closed
                        }
                    }
                }
            });
            RTCRtpSender sender = connection.addTrack(track, List.of("webdisplays"));
            tuneSender(sender);
        }

        private void tuneSender(RTCRtpSender sender) {
            try {
                RTCRtpSendParameters parameters = sender.getParameters();
                if (parameters.encodings != null) {
                    for (RTCRtpEncodingParameters encoding : parameters.encodings) {
                        encoding.maxBitrate = MAX_BITRATE;
                        encoding.maxFramerate = 60.0;
                    }
                    sender.setParameters(parameters);
                }
            } catch (Throwable error) {
                Log.warning("Could not tune WebRTC sender: {}", error.toString());
            }
        }

        void offer() {
            connection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    connection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            worker.execute(Peer.this::sendOffer);
                        }

                        @Override
                        public void onFailure(String error) {
                            Log.warning("WebRTC setLocalDescription failed for {}: {}", peerId, error);
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    Log.warning("WebRTC createOffer failed for {}: {}", peerId, error);
                }
            });
        }

        private void sendOffer() {
            try {
                gathered.await(4, TimeUnit.SECONDS);
                if (closed) return;
                RTCSessionDescription local = connection.getLocalDescription();
                JsonObject offer = new JsonObject();
                offer.addProperty("type", "offer");
                offer.addProperty("sdp", local.sdp);
                offerJson = offer.toString();
                sender.send(peerId, offerJson);
            } catch (Exception error) {
                Log.warning("Could not send WebRTC offer to {}: {}", peerId, error.toString());
            }
        }

        void resendOfferIfPending() {
            String json = offerJson;
            if (!closed && !answered && json != null) sender.send(peerId, json);
        }

        void acceptAnswer(String sdp) {
            if (closed || answered) return;
            answered = true;
            connection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp),
                    new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                        }

                        @Override
                        public void onFailure(String error) {
                            Log.warning("WebRTC setRemoteDescription failed for {}: {}", peerId, error);
                        }
                    });
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void close() {
        synchronized (frameLock) {
            running = false;
            if (lastBuffer != null) {
                lastBuffer.release();
                lastBuffer = null;
            }
        }
        keepAlive.shutdownNow();
        worker.shutdownNow();
        for (Peer peer : peers.values()) peer.close();
        peers.clear();
        try {
            track.dispose();
            source.dispose();
        } catch (Throwable ignored) {
        }
        Log.info("WebRTC stream stopped for session {}", sessionId);
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
