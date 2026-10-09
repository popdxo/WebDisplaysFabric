package net.montoyo.wd.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.montoyo.wd.utilities.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP control/signaling service. Media bytes must not use Minecraft play packets. */
public final class HybridWebService {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final HybridSessionManager sessions = new HybridSessionManager();
    private HttpServer server;
    private ExecutorService executor;

    public synchronized void start(String bindAddress, int port) {
        if (server != null) return;
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
            created.createContext("/health", exchange -> respond(exchange, 200,
                    "application/json; charset=utf-8", "{\"status\":\"ok\",\"service\":\"webdisplays-hybrid\"}"));
            created.createContext("/hybrid", this::handleViewer);
            created.createContext("/hybrid/session", this::handleSession);
            created.createContext("/hybrid/signal", this::handleSignal);
            created.createContext("/hybrid/frame", this::handleFrame);
            // Signaling uses 25 s long-polls that each park a thread, so a small fixed pool is starved by a few
            // viewers plus stale polls from restarted sessions (every request then times out).
            executor = new java.util.concurrent.ThreadPoolExecutor(4, 256, 60, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "WebDisplays-Hybrid-HTTP");
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(executor);
            created.start();
            server = created;
            Log.info("Hybrid web service listening on http://{}:{}/hybrid", bindAddress, created.getAddress().getPort());
        } catch (IOException exception) {
            stop();
            Log.error("Could not start Hybrid web service on {}:{}: {}", bindAddress, port, exception.getMessage());
        }
    }

    public HybridSessionManager.Session createSession(String displayKey) {
        return sessions.create(displayKey);
    }

    public HybridSessionManager.Session findSession(String id) {
        return sessions.find(id);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleViewer(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", viewerPage());
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        String body = readBody(exchange);
        String display = body == null || body.isBlank() ? "unknown" : body.trim();
        if (display.length() > 256 || display.indexOf('\n') >= 0) {
            respond(exchange, 400, "text/plain; charset=utf-8", "Invalid display key");
            return;
        }
        HybridSessionManager.Session session = sessions.create(display);
        String json = "{\"session\":\"" + escape(session.id()) + "\",\"ownerToken\":\""
                + escape(session.ownerToken()) + "\",\"viewerToken\":\"" + escape(session.viewerToken()) + "\"}";
        respond(exchange, 201, "application/json; charset=utf-8", json);
    }

    private void handleFrame(HttpExchange exchange) throws IOException {
        String[] path = exchange.getRequestURI().getPath().split("/");
        if (path.length < 4 || path[3].isBlank()) {
            respond(exchange, 400, "text/plain; charset=utf-8", "Missing session id");
            return;
        }
        HybridSessionManager.Session session = sessions.find(path[3]);
        if (session == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Session not found");
            return;
        }
        String token = bearer(exchange);
        if ("PUT".equals(exchange.getRequestMethod())) {
            if (!session.isOwner(token)) {
                respond(exchange, 401, "text/plain; charset=utf-8", "Owner token required");
                return;
            }
            byte[] bytes;
            try (var input = exchange.getRequestBody()) {
                bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            }
            if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024
                    || !"image/jpeg".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                respond(exchange, 413, "text/plain; charset=utf-8", "Invalid frame");
                return;
            }
            session.publishFrame(token, bytes);
            respond(exchange, 202, "text/plain; charset=utf-8", "Accepted");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            long version = session.latestFrameVersion(token);
            if (version < 0) {
                respond(exchange, 401, "text/plain; charset=utf-8", "Unauthorized");
                return;
            }
            String etag = "\"" + version + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] frame = session.latestFrame(token);
            if (frame == null) {
                respond(exchange, 204, "image/jpeg", "");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, frame.length);
            try (var output = exchange.getResponseBody()) { output.write(frame); }
            return;
        }
        respond(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
    }

    private void handleSignal(HttpExchange exchange) throws IOException {
        String[] path = exchange.getRequestURI().getPath().split("/");
        if (path.length < 4 || path[3].isBlank()) {
            respond(exchange, 400, "text/plain; charset=utf-8", "Missing session id");
            return;
        }
        HybridSessionManager.Session session = sessions.find(path[3]);
        if (session == null) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Session not found");
            return;
        }
        String token = bearer(exchange);
        if (!session.authorized(token)) {
            respond(exchange, 401, "text/plain; charset=utf-8", "Unauthorized");
            return;
        }
        String peerId = queryParameter(exchange, "peer");
        if (peerId == null || !peerId.matches("[A-Za-z0-9_-]{8,64}")) {
            respond(exchange, 400, "text/plain; charset=utf-8", "Missing or invalid peer id");
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            if (body == null || body.isBlank()) {
                respond(exchange, 400, "text/plain; charset=utf-8", "Empty signal");
                return;
            }
            boolean accepted = session.post(token, peerId, body);
            respond(exchange, accepted ? 202 : 429,
                    "text/plain; charset=utf-8", accepted ? "Accepted" : "Signal queue full");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            try {
                String message = session.poll(token, peerId, 25_000);
                respond(exchange, 200, "application/json; charset=utf-8",
                        message == null ? "null" : message);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                respond(exchange, 503, "text/plain; charset=utf-8", "Interrupted");
            }
            return;
        }
        respond(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (name.equals(key)) {
                try {
                    return java.net.URLDecoder.decode(equals < 0 ? "" : part.substring(equals + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String bearer(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        int length = exchange.getRequestHeaders().getFirst("Content-Length") == null ? -1
                : Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (length > MAX_BODY_BYTES) return null;
        try (var input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String viewerPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>WebDisplays Hybrid Test</title>"
                + "<style>*{box-sizing:border-box}html,body{width:100%;height:100%;margin:0;overflow:hidden;background:#000;color:#e8edf2;font:16px system-ui}main{width:100%;height:100%;max-width:none;margin:0;padding:0;position:relative}.card{background:#191f28;border:1px solid #344151;border-radius:8px;padding:1rem;margin:1rem 0}.switcher{display:flex;gap:.5rem}.switcher button{flex:1}.panel{display:none}.panel.active{display:block}input,button{box-sizing:border-box;margin:.25rem 0;padding:.65rem;background:#202733;color:#fff;border:1px solid #4b596b;border-radius:4px}input{width:100%}button{cursor:pointer;font-weight:600}button:hover{background:#2e3b4c}#stage{position:fixed;inset:0;width:100vw;height:100vh;background:#000;display:flex;align-items:center;justify-content:center}video,#frame{display:none;width:100%;height:100%;object-fit:cover;background:#000}#waiting{text-align:center;color:#e8edf2;padding:2rem}#waiting p{color:#aeb9c6}#log{white-space:pre-wrap;color:#aeb9c6;font-size:.85rem}.viewer-mode #ownerPanel,.viewer-mode #viewerPanel,.viewer-mode .switcher,.viewer-mode #context,.viewer-mode #session,.viewer-mode #token{display:none}.viewer-mode h1,.viewer-mode #log,.viewer-mode main>p{display:none}</style></head>"
                + "<body><main><h1>WebDisplays Hybrid Stream</h1><p>Owner shares a browser window. Viewers connect to that stream; they do not load the site independently.</p><p id=\"context\"></p>"
                + "<div class=\"card\"><h2>Session</h2><input id=\"session\" placeholder=\"Session ID\"><input id=\"token\" placeholder=\"Session token\"></div>"
                + "<div class=\"switcher\"><button id=\"ownerTab\" onclick=\"showMode('owner')\">Owner / Share</button><button id=\"viewerTab\" onclick=\"showMode('viewer')\">Viewer</button></div>"
                + "<div id=\"ownerPanel\" class=\"card panel\"><h2>Owner — share a browser window</h2><p>Open this owner link on the sharing computer, then approve the browser capture prompt.</p><button onclick=\"start('owner')\">Start sharing</button><button onclick=\"copyViewer()\">Copy viewer link</button></div>"
                + "<div id=\"viewerPanel\" class=\"card panel\"><h2>Viewer — watch the owner stream</h2><p>Use a viewer link. A viewer link connects automatically when opened.</p><button onclick=\"start('viewer')\">Connect as viewer</button></div><div id=\"stage\"><div id=\"waiting\"><h2>Waiting for screen share</h2><p id=\"status\">Connecting to the display owner…</p></div><video id=\"video\" autoplay playsinline></video><img id=\"frame\" alt=\"Live owner browser\"></div><pre id=\"log\"></pre></main>"
                + "<script>const $=id=>document.getElementById(id),log=x=>$('log').textContent+=x+'\\n';const base=location.origin,peerId=crypto.randomUUID().replaceAll('-','').slice(0,16);$('context').textContent='Secure context: '+window.isSecureContext+'; display capture available: '+!!(navigator.mediaDevices&&navigator.mediaDevices.getDisplayMedia);"
                + "try{let h=new URLSearchParams(location.hash.substring(1));$('session').value=h.get('session')||'';window.ownerToken=h.get('token')||'';window.viewerToken=h.get('viewerToken')||'';window.autoRole=h.get('role')||'';window.transport=h.get('transport')||'webrtc';$('token').value=window.ownerToken||window.viewerToken}catch(e){}"
                + "function showMode(role){$('ownerPanel').classList.toggle('active',role==='owner');$('viewerPanel').classList.toggle('active',role==='viewer');$('ownerTab').style.opacity=role==='owner'?'1':'.6';$('viewerTab').style.opacity=role==='viewer'?'1':'.6';document.body.classList.toggle('viewer-mode',role==='viewer')}showMode(window.autoRole==='viewer'?'viewer':'owner');"
                + "function copyViewer(){const t=window.viewerToken||$('token').value;if(!t){log('No viewer token available. Generate a fresh session from Minecraft.');return}let u=location.origin+'/hybrid#session='+encodeURIComponent($('session').value)+'&token='+encodeURIComponent(t)+'&role=viewer';navigator.clipboard.writeText(u).then(()=>log('Viewer link copied.')).catch(()=>log('Clipboard denied; copy this viewer URL manually: '+u))}"
                + "async function signal(method,body,peer=peerId){let o={method,headers:{Authorization:'Bearer '+$('token').value}};if(body){o.headers['Content-Type']='application/json';o.body=JSON.stringify(body)}let r=await fetch(base+'/hybrid/signal/'+encodeURIComponent($('session').value)+'?peer='+encodeURIComponent(peer),o);if(!r.ok)throw Error(await r.text());return r.status===202?null:r.json()}"
                + "async function waitIce(p){if(p.iceGatheringState==='complete')return;await new Promise(resolve=>{p.addEventListener('icegatheringstatechange',()=>{if(p.iceGatheringState==='complete')resolve()})})}"
                + "const sleep=ms=>new Promise(r=>setTimeout(r,ms));async function watchFrames(){if(window.frameWatchStarted)return;window.frameWatchStarted=true;const img=$('frame'),v=$('video'),token=window.viewerToken||$('token').value;let etag='';while(true){try{const headers={Authorization:'Bearer '+token};if(etag)headers['If-None-Match']=etag;const r=await fetch(base+'/hybrid/frame/'+encodeURIComponent($('session').value),{headers,cache:'no-store'});if(r.status===200){etag=r.headers.get('ETag')||etag;const blob=await r.blob(),old=img.src;img.src=URL.createObjectURL(blob);img.style.display='block';v.style.display='none';$('waiting').style.display='none';if(old)URL.revokeObjectURL(old);$('status').textContent='Live owner browser'}else if(img.style.display!=='block')$('waiting').style.display='flex'}catch(e){}await sleep(16)}}async function start(role){try{if(!$('session').value)throw Error('Missing session ID');const selectedToken=role==='owner'?(window.ownerToken||$('token').value):(window.viewerToken||$('token').value);if(!selectedToken)throw Error('Missing '+role+' token; use the matching '+role+' link');$('token').value=selectedToken;showMode(role);if(role==='owner'){  if(!window.isSecureContext||!navigator.mediaDevices||!navigator.mediaDevices.getDisplayMedia)throw Error('Screen capture is unavailable. Open owner page at http://localhost:<port>/hybrid.');const stream=await navigator.mediaDevices.getDisplayMedia({video:true,audio:false});$('video').srcObject=stream;const peers=new Map(),pending=new Map();log('Sharing started; waiting for viewers');while(true){const item=await signal('GET');if(!item){await sleep(100);continue}const id=item.peer,msg=item.message;if(!id||!msg)continue;if(msg.type==='join'&&!peers.has(id)&&!pending.has(id)){const pc=new RTCPeerConnection({iceServers:[]});peers.set(id,pc);pending.set(id,pc);pc.onconnectionstatechange=()=>log('viewer '+id+': '+pc.connectionState);stream.getTracks().forEach(t=>pc.addTrack(t,stream));void (async()=>{const offer=await pc.createOffer();await pc.setLocalDescription(offer);await waitIce(pc);await signal('POST',{type:'offer',sdp:pc.localDescription.sdp},id);log('Offer sent to viewer '+id)})().catch(e=>log('ERROR viewer '+id+': '+(e.message||e)));}else if(msg.type==='answer'&&pending.has(id)){const pc=pending.get(id);pending.delete(id);await pc.setRemoteDescription(msg);log('Viewer connected: '+id)}}}else{if(role==='viewer'){window.viewerToken=selectedToken;if(window.transport==='jpeg'){$('status').textContent='Waiting for the owner browser…';void watchFrames();return}setTimeout(()=>{if(!window.gotTrack){log('No WebRTC video yet; also polling legacy JPEG frames');void watchFrames()}},8000)}const pc=new RTCPeerConnection({iceServers:[{urls:'stun:stun.l.google.com:19302'}]});pc.ontrack=e=>{window.gotTrack=true;$('frame').style.display='none';const v=$('video');v.srcObject=e.streams[0]||new MediaStream([e.track]);v.style.display='block';$('waiting').style.display='none';v.play().catch(()=>{})};pc.onconnectionstatechange=()=>{$('status').textContent='Connection: '+pc.connectionState;if(pc.connectionState==='failed'||pc.connectionState==='disconnected'){$('status').textContent='Stream connection lost. Reconnecting…';$('waiting').style.display='flex';if(pc.connectionState==='failed')setTimeout(()=>location.reload(),2000)}};await signal('POST',{type:'join'});$('status').textContent='Waiting for the owner to start screen sharing…';log('Viewer joined; waiting for offer');let offer;while(!offer){offer=await signal('GET');if(!offer)await sleep(250)}await pc.setRemoteDescription(offer);const answer=await pc.createAnswer();await pc.setLocalDescription(answer);await waitIce(pc);await signal('POST',{type:'answer',sdp:pc.localDescription.sdp});log('Answer sent; connecting')}}catch(e){log('ERROR: '+(e.name||'Error')+': '+(e.message||e))}};if(window.autoRole==='viewer')start('viewer');"
                + "</script></body></html>";
    }
}
