package net.montoyo.wd.client.mcef;

import net.montoyo.wd.utilities.Log;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.DataBufferInt;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures only explicitly registered owner browsers. Frames are published as a WebRTC video track
 * ({@link WebRtcOwnerStream}). The JPEG-over-HTTP relay below is kept only as a temporary fallback for when the
 * WebRTC native library cannot be loaded, and is to be removed once WebRTC is verified in-game.
 * Set {@code -Dwebdisplays.stream.transport=jpeg} to force the legacy relay.
 */
public final class HybridFrameCapture {
    private static final Map<Object, Source> SOURCES = new ConcurrentHashMap<>();
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WebDisplays-Hybrid-Frame-Encoder");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile long lastCaptureAtNanos;
    private static final AtomicBoolean FRAME_BUSY = new AtomicBoolean();

    private static final boolean FORCE_JPEG = "jpeg".equalsIgnoreCase(System.getProperty("webdisplays.stream.transport"));
    /** One WebRTC stream per Hybrid session; it follows whichever browser (tab) of the display is registered. */
    private static final Map<String, WebRtcOwnerStream> RTC_STREAMS = new ConcurrentHashMap<>();
    private static final Map<Object, WebRtcOwnerStream> RTC_BROWSERS = new ConcurrentHashMap<>();

    private HybridFrameCapture() {}

    /** Delivers a signaling message from the owner to one viewer (through the server). */
    @FunctionalInterface
    public interface SignalSender {
        void send(String peerId, String json);
    }

    /** Idempotent: called every client tick for owner displays with the display's current browser. */
    public static synchronized void register(Object browser, String baseUrl, String sessionId, String ownerToken,
                                             SignalSender sender) {
        if (browser == null || baseUrl == null || sessionId == null || ownerToken == null) return;
        if (!FORCE_JPEG) {
            WebRtcOwnerStream stream = RTC_STREAMS.get(sessionId);
            if (stream == null) {
                stream = WebRtcOwnerStream.create(sessionId, sender);
                if (stream != null) RTC_STREAMS.put(sessionId, stream);
            }
            if (stream != null) {
                if (RTC_BROWSERS.get(browser) != stream) {
                    // Active tab changed (or first registration): move the stream to this browser.
                    final WebRtcOwnerStream owner = stream;
                    RTC_BROWSERS.values().removeIf(existing -> existing == owner);
                    RTC_BROWSERS.put(browser, stream);
                    SOURCES.remove(browser);
                    stream.setRepaintHook(() -> MCEFHelper.requestRepaint(browser));
                    MCEFHelper.requestRepaint(browser); // a static page never paints on its own
                }
                return;
            }
        }
        SOURCES.put(browser, new Source(baseUrl, sessionId, ownerToken));
    }

    /** Called when a browser is closed; the session's stream stays up and moves to the next registered browser. */
    public static synchronized void unregister(Object browser) {
        if (browser == null) return;
        SOURCES.remove(browser);
        RTC_BROWSERS.remove(browser);
    }

    /** A signaling message from a viewer arrived (relayed by the server). */
    public static void onSignal(String sessionId, String peerId, String json) {
        WebRtcOwnerStream stream = sessionId == null ? null : RTC_STREAMS.get(sessionId);
        if (stream != null) {
            stream.onSignal(peerId, json);
        } else {
            Log.warning("Hybrid signal for session {} ignored: no stream running (active: {})", sessionId, RTC_STREAMS.keySet());
        }
    }

    /** Ends a Hybrid session on the owner side (mode changed or display removed). */
    public static synchronized void stopSession(String sessionId) {
        if (sessionId == null) return;
        WebRtcOwnerStream stream = RTC_STREAMS.remove(sessionId);
        if (stream != null) {
            RTC_BROWSERS.values().removeIf(existing -> existing == stream);
            stream.close();
        }
        SOURCES.values().removeIf(source -> sessionId.equals(source.sessionId));
    }

    public static void onPaint(Object browser, boolean popup, java.awt.Rectangle[] dirtyRects,
                               ByteBuffer pixels, int width, int height) {
        WebRtcOwnerStream stream = RTC_BROWSERS.get(browser);
        if (stream != null) {
            if (!popup && pixels != null && width >= 2 && height >= 2 && width <= 4096 && height <= 4096
                    && pixels.remaining() >= (long) width * height * 4) {
                stream.pushPaint(pixels, width, height);
            }
            return;
        }
        Source source = SOURCES.get(browser);
        if (source == null || popup || pixels == null || width < 1 || height < 1
                || width > 4096 || height > 4096 || (long) width * height > 8_000_000L
                || pixels.remaining() < (long) width * height * 4) return;
        long now = System.nanoTime();
        if (now - lastCaptureAtNanos < 16_666_667L || !FRAME_BUSY.compareAndSet(false, true)) return;
        lastCaptureAtNanos = now;
        double scale = Math.min(1.0, Math.min(960.0 / width, 540.0 / height));
        int outWidth = Math.max(1, (int) (width * scale));
        int outHeight = Math.max(1, (int) (height * scale));
        int[] rgb = new int[outWidth * outHeight];
        ByteBuffer copy = pixels.asReadOnlyBuffer();
        int sourceOffset = copy.position();
        for (int y = 0; y < outHeight; y++) {
            int sy = Math.min(height - 1, (int) (y * (height / (double) outHeight)));
            for (int x = 0; x < outWidth; x++) {
                int sx = Math.min(width - 1, (int) (x * (width / (double) outWidth)));
                int from = (sy * width + sx) * 4;
                int b = copy.get(sourceOffset + from) & 255;
                int g = copy.get(sourceOffset + from + 1) & 255;
                int r = copy.get(sourceOffset + from + 2) & 255;
                rgb[y * outWidth + x] = (r << 16) | (g << 8) | b;
            }
        }
        ENCODER.execute(() -> {
            try {
                BufferedImage image = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
                int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                System.arraycopy(rgb, 0, imagePixels, 0, rgb.length);
                ByteArrayOutputStream output = new ByteArrayOutputStream(256_000);
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                try (MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
                    writer.setOutput(imageOutput);
                    ImageWriteParam parameters = writer.getDefaultWriteParam();
                    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    parameters.setCompressionQuality(0.65f);
                    writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
                    imageOutput.flush();
                } finally {
                    writer.dispose();
                }
                if (output.size() > 2 * 1024 * 1024) return;
                upload(source, output.toByteArray());
            } catch (Exception exception) {
                Log.warning("Hybrid browser frame relay failed: {}", exception.getMessage());
            } finally {
                FRAME_BUSY.set(false);
            }
        });
    }

    private static void upload(Source source, byte[] jpeg) throws Exception {
        URL url = new URL(source.baseUrl + "/hybrid/frame/" + source.sessionId);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(jpeg.length);
            connection.setRequestProperty("Content-Type", "image/jpeg");
            connection.setRequestProperty("Authorization", "Bearer " + source.ownerToken);
            try (var stream = connection.getOutputStream()) { stream.write(jpeg); }
            connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private record Source(String baseUrl, String sessionId, String ownerToken) {}
}
