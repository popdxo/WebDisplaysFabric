package net.montoyo.wd.stream;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HybridSessionManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_QUEUED_MESSAGES = 32;
    private static final long SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session create(String displayKey) {
        pruneExpired();
        Session session = new Session(UUID.randomUUID().toString(), displayKey, token(), token());
        sessions.put(session.id(), session);
        return session;
    }

    public Session find(String id) {
        Session session = sessions.get(id);
        if (session != null && System.currentTimeMillis() - session.lastActivity() > SESSION_IDLE_TIMEOUT_MS) {
            sessions.remove(id, session);
            return null;
        }
        return session;
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> now - entry.getValue().lastActivity() > SESSION_IDLE_TIMEOUT_MS);
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static final class Session {
        private final String id;
        private final String displayKey;
        private final String ownerToken;
        private final String viewerToken;
        private final ArrayDeque<Message> ownerInbox = new ArrayDeque<>();
        private final Map<String, ArrayDeque<String>> viewerInboxes = new ConcurrentHashMap<>();
        private volatile long lastActivity = System.currentTimeMillis();
        private volatile byte[] latestFrame;
        private volatile long latestFrameAt;
        private volatile long latestFrameVersion;

        private Session(String id, String displayKey, String ownerToken, String viewerToken) {
            this.id = id;
            this.displayKey = displayKey;
            this.ownerToken = ownerToken;
            this.viewerToken = viewerToken;
        }

        public String id() { return id; }
        public String displayKey() { return displayKey; }
        public String ownerToken() { return ownerToken; }
        public String viewerToken() { return viewerToken; }
        public long lastActivity() { return lastActivity; }
        public synchronized boolean isOwner(String token) { return ownerToken.equals(token); }
        public synchronized boolean isViewer(String token) { return viewerToken.equals(token); }
        public synchronized boolean authorized(String token) { return isOwner(token) || isViewer(token); }

        public synchronized boolean publishFrame(String token, byte[] frame) {
            if (!isOwner(token) || frame == null || frame.length == 0 || frame.length > 2 * 1024 * 1024) return false;
            latestFrame = frame;
            latestFrameAt = System.currentTimeMillis();
            latestFrameVersion++;
            lastActivity = latestFrameAt;
            return true;
        }

        public long latestFrameVersion(String token) {
            return (isViewer(token) || isOwner(token)) ? latestFrameVersion : -1;
        }

        public byte[] latestFrame(String token) {
            if (!isViewer(token) && !isOwner(token)) return null;
            byte[] frame = latestFrame;
            return frame != null && System.currentTimeMillis() - latestFrameAt < 10_000 ? frame : null;
        }

        public synchronized boolean post(String token, String peerId, String message) {
            if (peerId == null || !peerId.matches("[A-Za-z0-9_-]{8,64}") || message == null) return false;
            if (isOwner(token)) {
                ArrayDeque<String> inbox = viewerInboxes.computeIfAbsent(peerId, ignored -> new ArrayDeque<>());
                if (inbox.size() >= MAX_QUEUED_MESSAGES) return false;
                inbox.addLast(message);
            } else if (isViewer(token)) {
                if (ownerInbox.size() >= MAX_QUEUED_MESSAGES) return false;
                ownerInbox.addLast(new Message(peerId, message));
            } else return false;
            lastActivity = System.currentTimeMillis();
            notifyAll();
            return true;
        }

        public synchronized String poll(String token, String peerId, long timeoutMillis) throws InterruptedException {
            if (!authorized(token) || peerId == null || !peerId.matches("[A-Za-z0-9_-]{8,64}")) return null;
            boolean owner = isOwner(token);
            lastActivity = System.currentTimeMillis(); // a connected owner keeps its session alive
            long deadline = System.currentTimeMillis() + timeoutMillis;
            if (owner) {
                while (ownerInbox.isEmpty() && System.currentTimeMillis() < deadline) {
                    wait(Math.max(1, deadline - System.currentTimeMillis()));
                }
                Message message = ownerInbox.pollFirst();
                if (message == null) return null;
                return "{\"peer\":\"" + escape(message.peerId) + "\",\"message\":" + message.body + "}";
            }
            ArrayDeque<String> inbox = viewerInboxes.computeIfAbsent(peerId, ignored -> new ArrayDeque<>());
            while (inbox.isEmpty() && System.currentTimeMillis() < deadline) {
                wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
            lastActivity = System.currentTimeMillis();
            return inbox.pollFirst();
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private record Message(String peerId, String body) {}
    }
}
