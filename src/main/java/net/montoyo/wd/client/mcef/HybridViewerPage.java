package net.montoyo.wd.client.mcef;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The Hybrid viewer page, shipped inside the client and loaded as a data: URL so it needs no reachable web
 * server. It talks to the game through console messages ({@code __WD_SIG__|peer|json}) and receives replies via
 * {@code window.__wdSignal(json)}; the game relays both over the Minecraft connection.
 */
public final class HybridViewerPage {
    public static final String SIGNAL_PREFIX = "__WD_SIG__|";
    public static final String LOG_PREFIX = "__WD_LOG__|";

    private static final String HTML = """
            <!doctype html><html><head><meta charset="utf-8"><title>WebDisplays Hybrid</title>
            <style>
            html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden;color:#e8edf2;font:16px system-ui}
            video{display:none;width:100%;height:100%;object-fit:cover;background:#000}
            #waiting{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;text-align:center}
            #waiting p{color:#aeb9c6}
            </style></head><body>
            <div id="waiting"><div><h2>Waiting for screen share</h2><p id="status">Connecting to the display owner\u2026</p></div></div>
            <video id="video" autoplay playsinline muted></video>
            <script>
            const log = m => console.log('__WD_LOG__|' + m);
            window.addEventListener('error', e => log('script error: ' + e.message));
            window.addEventListener('unhandledrejection', e => log('async error: ' + (e.reason && e.reason.message || e.reason)));
            // data: pages are not a secure context, so crypto.randomUUID() does not exist here.
            const peer = Array.from(crypto.getRandomValues(new Uint8Array(8)), b => b.toString(16).padStart(2, '0')).join('');
            const $ = id => document.getElementById(id);
            const out = message => console.log('__WD_SIG__|' + peer + '|' + JSON.stringify(message));
            let pc = null, joinTimer = null;
            const waitIce = p => new Promise(resolve => {
              if (p.iceGatheringState === 'complete') return resolve();
              const done = () => { if (p.iceGatheringState === 'complete') resolve(); };
              p.addEventListener('icegatheringstatechange', done);
              setTimeout(resolve, 4000);
            });
            function join() { if (!pc) out({type: 'join'}); }
            async function onOffer(message) {
              if (pc) return;
              clearInterval(joinTimer);
              pc = new RTCPeerConnection({iceServers: [{urls: 'stun:stun.l.google.com:19302'}]});
              log('offer received');
              pc.ontrack = e => {
                log('video track received');
                const v = $('video');
                v.srcObject = e.streams[0] || new MediaStream([e.track]);
                v.style.display = 'block';
                $('waiting').style.display = 'none';
                v.play().catch(() => {});
              };
              pc.onconnectionstatechange = () => {
                const state = pc.connectionState;
                log('connection ' + state);
                if (state === 'failed') {
                  $('waiting').style.display = 'flex';
                  $('status').textContent = 'Stream connection failed. Retrying\u2026';
                  setTimeout(() => location.reload(), 2000);
                } else if (state === 'disconnected') {
                  $('status').textContent = 'Stream connection lost. Reconnecting\u2026';
                }
              };
              await pc.setRemoteDescription({type: 'offer', sdp: message.sdp});
              await pc.setLocalDescription(await pc.createAnswer());
              await waitIce(pc);
              out({type: 'answer', sdp: pc.localDescription.sdp});
              log('answer sent');
              $('status').textContent = 'Connecting to the stream\u2026';
            }
            window.__wdSignal = function (json) {
              try {
                const message = JSON.parse(json);
                if (message.type === 'offer') onOffer(message);
              } catch (e) { console.log('WebDisplays viewer error: ' + e); }
            };
            log('viewer page loaded, peer ' + peer + ', secure context ' + window.isSecureContext);
            join();
            joinTimer = setInterval(join, 3000);
            </script></body></html>
            """;

    private HybridViewerPage() {}

    public static String dataUrl() {
        return "data:text/html;base64," + Base64.getEncoder().encodeToString(HTML.getBytes(StandardCharsets.UTF_8));
    }
}
