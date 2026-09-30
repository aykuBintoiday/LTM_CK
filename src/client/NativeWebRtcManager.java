package client;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebRTC Native bridge loaded by reflection.
 *
 * Why reflection?
 * - The project can still be compiled with plain JDK 17 even before the WebRTC jars are downloaded.
 * - At runtime, put webrtc-java-0.18.0.jar and the platform native jar in lib/.
 *
 * No browser/HTML is used. Camera/microphone and RTCPeerConnection are native WebRTC.
 */
public class NativeWebRtcManager implements AutoCloseable {
    public interface VideoCallback { void onFrame(String peerEmail, BufferedImage image); }
    public interface SignalCallback {
        void sendOffer(String targetEmail, String sdp);
        void sendAnswer(String targetEmail, String sdp);
        void sendIce(String targetEmail, String sdpMid, int sdpMLineIndex, String candidate);
    }
    public interface StatusCallback { void onStatus(String message); }

    public static final String LOCAL_PEER = "__local__";

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Object> videoSinkRefs = new ConcurrentHashMap<>();
    private final Map<String, Object> videoSinkTrackRefs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFrameNs = new ConcurrentHashMap<>();

    private Object factory;
    private Object audioSource;
    private Object audioTrack;
    private Object videoSource;
    private Object videoTrack;
    private Object screenSource;
    private Object screenTrack;
    private volatile boolean screenSharing;
    private SignalCallback signals;
    private VideoCallback videoCallback;
    private StatusCallback statusCallback;
    private volatile boolean initialized;

    private Class<?> clsFactory;
    private Class<?> clsRtcConfig;
    private Class<?> clsIceServer;
    private Class<?> clsPeerObserver;
    private Class<?> clsOfferOptions;
    private Class<?> clsAnswerOptions;
    private Class<?> clsCreateSdpObserver;
    private Class<?> clsSetSdpObserver;
    private Class<?> clsSessionDescription;
    private Class<?> clsSdpType;
    private Class<?> clsIceCandidate;
    private Class<?> clsVideoSink;

    public boolean initialize(VideoCallback videoCallback, SignalCallback signals, StatusCallback statusCallback) {
        if (initialized) return true;
        this.videoCallback = videoCallback;
        this.signals = signals;
        this.statusCallback = statusCallback;
        try {
            loadClasses();
            factory = clsFactory.getConstructor().newInstance();

            // Microphone with echo cancellation, AGC and noise suppression.
            Class<?> clsAudioOptions = Class.forName("dev.onvoid.webrtc.media.audio.AudioOptions");
            Object audioOptions = clsAudioOptions.getConstructor().newInstance();
            setPublicFieldIfExists(audioOptions, "echoCancellation", true);
            setPublicFieldIfExists(audioOptions, "autoGainControl", true);
            setPublicFieldIfExists(audioOptions, "noiseSuppression", true);
            audioSource = invokeByName(factory, "createAudioSource", audioOptions);
            audioTrack = invokeByName(factory, "createAudioTrack", "audio0", audioSource);

            // Default webcam.
            Class<?> clsVideoSource = Class.forName("dev.onvoid.webrtc.media.video.VideoDeviceSource");
            videoSource = clsVideoSource.getConstructor().newInstance();
            invokeByName(videoSource, "start");
            videoTrack = invokeByName(factory, "createVideoTrack", "video0", videoSource);
            attachVideoSink(videoTrack, LOCAL_PEER);

            initialized = true;
            status("WebRTC Native đã sẵn sàng: camera + microphone chạy trực tiếp trong Java Desktop.");
            return true;
        } catch (Throwable e) {
            status("Không khởi tạo được WebRTC Native: " + rootMessage(e) + ". Hãy chạy SETUP_LIBS.bat rồi mở lại Client.");
            safeDisposeMedia();
            return false;
        }
    }

    public boolean isInitialized() { return initialized; }

    public synchronized void ensurePeer(String remoteEmail, boolean initiator) {
        if (!initialized || remoteEmail == null || remoteEmail.isBlank()) return;
        remoteEmail = remoteEmail.trim().toLowerCase(Locale.ROOT);
        if (peers.containsKey(remoteEmail)) return;
        try {
            final String key = remoteEmail;
            Object config = clsRtcConfig.getConstructor().newInstance();
            Object iceServer = clsIceServer.getConstructor().newInstance();
            @SuppressWarnings("unchecked") List<String> urls = (List<String>) clsIceServer.getField("urls").get(iceServer);
            urls.add("stun:stun.l.google.com:19302");
            @SuppressWarnings("unchecked") List<Object> iceServers = (List<Object>) clsRtcConfig.getField("iceServers").get(config);
            iceServers.add(iceServer);

            Object observer = Proxy.newProxyInstance(clsPeerObserver.getClassLoader(), new Class[]{clsPeerObserver}, (proxy, method, args) -> {
                String name = method.getName();
                try {
                    if ("onIceCandidate".equals(name) && args != null && args.length > 0 && args[0] != null) {
                        Object cand = args[0];
                        String mid = String.valueOf(cand.getClass().getField("sdpMid").get(cand));
                        int index = cand.getClass().getField("sdpMLineIndex").getInt(cand);
                        String sdp = String.valueOf(cand.getClass().getField("sdp").get(cand));
                        if (signals != null) signals.sendIce(key, mid, index, sdp);
                    } else if ("onTrack".equals(name) && args != null && args.length > 0 && args[0] != null) {
                        Object transceiver = args[0];
                        Object receiver = invokeByName(transceiver, "getReceiver");
                        Object track = invokeByName(receiver, "getTrack");
                        onRemoteTrack(key, track);
                    } else if ("onAddTrack".equals(name) && args != null && args.length > 0 && args[0] != null) {
                        Object track = invokeByName(args[0], "getTrack");
                        onRemoteTrack(key, track);
                    } else if ("onConnectionChange".equals(name) && args != null && args.length > 0) {
                        status("WebRTC " + key + ": " + args[0]);
                    }
                } catch (Throwable ex) {
                    status("WebRTC callback " + key + " lỗi: " + rootMessage(ex));
                }
                return defaultValue(method.getReturnType());
            });

            Object pc = invokeByName(factory, "createPeerConnection", config, observer);
            Object audioSender = invokeByName(pc, "addTrack", audioTrack, List.of("stream0"));
            Object outgoingVideo = screenSharing && screenTrack != null ? screenTrack : videoTrack;
            Object videoSender = invokeByName(pc, "addTrack", outgoingVideo, List.of("stream0"));
            Peer peer = new Peer(key, pc, audioSender, videoSender);
            peers.put(key, peer);
            status("Đã tạo PeerConnection với " + key);
            if (initiator) createOffer(peer);
        } catch (Throwable e) {
            status("Không tạo được PeerConnection với " + remoteEmail + ": " + rootMessage(e));
        }
    }

    public void handleOffer(String fromEmail, String sdp) {
        if (!initialized) return;
        String key = norm(fromEmail);
        ensurePeer(key, false);
        Peer peer = peers.get(key);
        if (peer == null) return;
        try {
            Object desc = newSessionDescription("OFFER", sdp);
            setRemoteDescription(peer, desc, () -> createAnswer(peer));
        } catch (Throwable e) { status("Xử lý OFFER lỗi: " + rootMessage(e)); }
    }

    public void handleAnswer(String fromEmail, String sdp) {
        if (!initialized) return;
        Peer peer = peers.get(norm(fromEmail));
        if (peer == null) return;
        try {
            Object desc = newSessionDescription("ANSWER", sdp);
            setRemoteDescription(peer, desc, null);
        } catch (Throwable e) { status("Xử lý ANSWER lỗi: " + rootMessage(e)); }
    }

    public void handleIce(String fromEmail, String sdpMid, int index, String candidate) {
        if (!initialized) return;
        String key = norm(fromEmail);
        ensurePeer(key, false);
        Peer peer = peers.get(key);
        if (peer == null) return;
        IceData data = new IceData(sdpMid, index, candidate);
        synchronized (peer) {
            if (!peer.remoteDescriptionSet) {
                peer.pendingIce.add(data);
                return;
            }
        }
        addIce(peer, data);
    }

    public void setMicrophoneEnabled(boolean enabled) {
        try { if (audioTrack != null) invokeByName(audioTrack, "setEnabled", enabled); }
        catch (Throwable e) { status("Không đổi được trạng thái microphone: " + rootMessage(e)); }
    }

    public void setCameraEnabled(boolean enabled) {
        try { if (videoTrack != null) invokeByName(videoTrack, "setEnabled", enabled); }
        catch (Throwable e) { status("Không đổi được trạng thái camera: " + rootMessage(e)); }
    }

    public boolean isScreenSharing() { return screenSharing; }

    /** Returns the full desktop screens available for sharing. */
    public List<ScreenSource> listScreens() {
        List<ScreenSource> result = new ArrayList<>();
        Object capturer = null;
        try {
            Class<?> clsScreenCapturer = Class.forName("dev.onvoid.webrtc.media.video.desktop.ScreenCapturer");
            capturer = clsScreenCapturer.getConstructor().newInstance();
            Object sourcesObj = invokeByName(capturer, "getDesktopSources");
            if (sourcesObj instanceof Iterable<?> iterable) {
                int fallback = 1;
                for (Object src : iterable) {
                    if (src == null) continue;
                    Object rawId = readFieldOrGetter(src, "id", "getId");
                    long id = rawId instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(rawId));
                    Object rawTitle = readFieldOrGetter(src, "title", "getTitle");
                    String title = rawTitle == null || String.valueOf(rawTitle).isBlank()
                            ? "Màn hình " + fallback : String.valueOf(rawTitle);
                    result.add(new ScreenSource(id, title));
                    fallback++;
                }
            }
        } catch (Throwable e) {
            status("Không lấy được danh sách màn hình: " + rootMessage(e));
        } finally {
            if (capturer != null) try { invokeByName(capturer, "dispose"); } catch (Throwable ignored) {}
        }
        return result;
    }

    /**
     * Replaces the outgoing camera video with a desktop-capture video track.
     * Audio keeps using the microphone. No browser/HTML is involved.
     */
    public synchronized boolean startScreenShare(long sourceId) {
        if (!initialized) return false;
        if (screenSharing) return true;
        Object newSource = null;
        Object newTrack = null;
        try {
            Class<?> clsDesktopSource = Class.forName("dev.onvoid.webrtc.media.video.VideoDesktopSource");
            newSource = clsDesktopSource.getConstructor().newInstance();
            try { invokeByName(newSource, "setFrameRate", 15); } catch (Throwable ignored) {}
            try { invokeByName(newSource, "setMaxFrameSize", 1280, 720); } catch (Throwable ignored) {}
            invokeByName(newSource, "setSourceId", sourceId, false);
            invokeByName(newSource, "start");
            newTrack = invokeByName(factory, "createVideoTrack", "screen0_" + System.nanoTime(), newSource);

            screenSource = newSource;
            screenTrack = newTrack;
            // Switch every existing peer to the screen track.
            for (Peer peer : peers.values()) replaceVideoTrack(peer, newTrack);

            screenSharing = true;
            attachVideoSink(screenTrack, LOCAL_PEER);
            status("Đang chia sẻ màn hình qua WebRTC.");
            return true;
        } catch (Throwable e) {
            status("Không thể chia sẻ màn hình: " + rootMessage(e));
            for (Peer peer : peers.values()) { try { replaceVideoTrack(peer, videoTrack); } catch (Throwable ignored) {} }
            screenSharing = false; screenTrack = null; screenSource = null;
            if (newTrack != null) try { invokeByName(newTrack, "dispose"); } catch (Throwable ignored) {}
            if (newSource != null) {
                try { invokeByName(newSource, "stop"); } catch (Throwable ignored) {}
                try { invokeByName(newSource, "dispose"); } catch (Throwable ignored) {}
            }
            try { if (videoTrack != null) attachVideoSink(videoTrack, LOCAL_PEER); } catch (Throwable ignored) {}
            return false;
        }
    }

    public synchronized void stopScreenShare() {
        if (!screenSharing && screenSource == null && screenTrack == null) return;
        Object oldTrack = screenTrack;
        Object oldSource = screenSource;
        screenSharing = false;
        screenTrack = null;
        screenSource = null;
        try {
            for (Peer peer : peers.values()) replaceVideoTrack(peer, videoTrack);
            if (videoTrack != null) attachVideoSink(videoTrack, LOCAL_PEER);
            status("Đã dừng chia sẻ màn hình, quay lại camera.");
        } catch (Throwable e) {
            status("Dừng chia sẻ màn hình có lỗi: " + rootMessage(e));
        } finally {
            if (oldTrack != null) try { invokeByName(oldTrack, "dispose"); } catch (Throwable ignored) {}
            if (oldSource != null) {
                try { invokeByName(oldSource, "stop"); } catch (Throwable ignored) {}
                try { invokeByName(oldSource, "dispose"); } catch (Throwable ignored) {}
            }
        }
    }

    private void replaceVideoTrack(Peer peer, Object newTrack) throws Exception {
        if (peer == null || newTrack == null) return;
        if (peer.videoSender != null) {
            try {
                invokeByName(peer.videoSender, "replaceTrack", newTrack);
                return;
            } catch (NoSuchMethodException ignored) {
                try {
                    invokeByName(peer.videoSender, "setTrack", newTrack);
                    return;
                } catch (NoSuchMethodException ignored2) {
                    // Fall through to remove/add + renegotiation.
                }
            }
        }
        try { if (peer.videoSender != null) invokeByName(peer.pc, "removeTrack", peer.videoSender); } catch (Throwable ignored) {}
        peer.videoSender = invokeByName(peer.pc, "addTrack", newTrack, List.of("stream0"));
        createOffer(peer);
    }

    public synchronized void removePeer(String email) {
        Peer p = peers.remove(norm(email));
        if (p != null) {
            try { if (p.audioSender != null) invokeByName(p.audioSender, "dispose"); } catch (Throwable ignored) {}
            try { if (p.videoSender != null) invokeByName(p.videoSender, "dispose"); } catch (Throwable ignored) {}
            try { invokeByName(p.pc, "close"); } catch (Throwable ignored) {}
        }
        detachVideoSink(norm(email));
        lastFrameNs.remove(norm(email));
    }

    private void createOffer(Peer peer) {
        try {
            Object options = clsOfferOptions.getConstructor().newInstance();
            Object observer = createSdpObserver(sdp -> {
                try {
                    setLocalDescription(peer, sdp, () -> {
                        try {
                            String text = String.valueOf(sdp.getClass().getField("sdp").get(sdp));
                            if (signals != null) signals.sendOffer(peer.email, text);
                        } catch (Throwable e) { status("Không gửi được OFFER: " + rootMessage(e)); }
                    });
                } catch (Throwable e) { status("Set local OFFER lỗi: " + rootMessage(e)); }
            });
            invokeByName(peer.pc, "createOffer", options, observer);
        } catch (Throwable e) { status("Create OFFER lỗi: " + rootMessage(e)); }
    }

    private void createAnswer(Peer peer) {
        try {
            Object options = clsAnswerOptions.getConstructor().newInstance();
            Object observer = createSdpObserver(sdp -> {
                try {
                    setLocalDescription(peer, sdp, () -> {
                        try {
                            String text = String.valueOf(sdp.getClass().getField("sdp").get(sdp));
                            if (signals != null) signals.sendAnswer(peer.email, text);
                        } catch (Throwable e) { status("Không gửi được ANSWER: " + rootMessage(e)); }
                    });
                } catch (Throwable e) { status("Set local ANSWER lỗi: " + rootMessage(e)); }
            });
            invokeByName(peer.pc, "createAnswer", options, observer);
        } catch (Throwable e) { status("Create ANSWER lỗi: " + rootMessage(e)); }
    }

    private Object createSdpObserver(java.util.function.Consumer<Object> success) {
        return Proxy.newProxyInstance(clsCreateSdpObserver.getClassLoader(), new Class[]{clsCreateSdpObserver}, (proxy, method, args) -> {
            if ("onSuccess".equals(method.getName()) && args != null && args.length > 0) success.accept(args[0]);
            else if ("onFailure".equals(method.getName()) && args != null && args.length > 0) status("WebRTC SDP lỗi: " + args[0]);
            return defaultValue(method.getReturnType());
        });
    }

    private Object setSdpObserver(Runnable success) {
        return Proxy.newProxyInstance(clsSetSdpObserver.getClassLoader(), new Class[]{clsSetSdpObserver}, (proxy, method, args) -> {
            if ("onSuccess".equals(method.getName()) && success != null) success.run();
            else if ("onFailure".equals(method.getName()) && args != null && args.length > 0) status("WebRTC set SDP lỗi: " + args[0]);
            return defaultValue(method.getReturnType());
        });
    }

    private void setLocalDescription(Peer peer, Object desc, Runnable onSuccess) throws Exception {
        invokeByName(peer.pc, "setLocalDescription", desc, setSdpObserver(onSuccess));
    }

    private void setRemoteDescription(Peer peer, Object desc, Runnable after) throws Exception {
        invokeByName(peer.pc, "setRemoteDescription", desc, setSdpObserver(() -> {
            synchronized (peer) { peer.remoteDescriptionSet = true; }
            flushIce(peer);
            if (after != null) after.run();
        }));
    }

    private void addIce(Peer peer, IceData data) {
        try {
            Object candidate = clsIceCandidate.getConstructor(String.class, int.class, String.class)
                    .newInstance(data.mid, data.index, data.candidate);
            invokeByName(peer.pc, "addIceCandidate", candidate);
        } catch (Throwable e) { status("ICE candidate lỗi: " + rootMessage(e)); }
    }

    private void flushIce(Peer peer) {
        List<IceData> copy;
        synchronized (peer) { copy = new ArrayList<>(peer.pendingIce); peer.pendingIce.clear(); }
        for (IceData x : copy) addIce(peer, x);
    }

    private Object newSessionDescription(String enumName, String sdp) throws Exception {
        @SuppressWarnings({"unchecked","rawtypes"}) Object type = Enum.valueOf((Class<? extends Enum>) clsSdpType.asSubclass(Enum.class), enumName);
        return clsSessionDescription.getConstructor(clsSdpType, String.class).newInstance(type, sdp);
    }

    private void onRemoteTrack(String peerEmail, Object track) {
        try {
            if (track == null) return;
            String kind = String.valueOf(invokeByName(track, "getKind"));
            if ("video".equalsIgnoreCase(kind)) {
                attachVideoSink(track, peerEmail);
                status("Đã nhận/cập nhật video track từ " + peerEmail);
            }
        } catch (Throwable e) { status("Remote track lỗi: " + rootMessage(e)); }
    }

    private synchronized void attachVideoSink(Object track, String peerKey) throws Exception {
        detachVideoSink(peerKey);
        Object sink = Proxy.newProxyInstance(clsVideoSink.getClassLoader(), new Class[]{clsVideoSink}, (proxy, method, args) -> {
            if ("onVideoFrame".equals(method.getName()) && args != null && args.length > 0 && args[0] != null) {
                long now = System.nanoTime();
                long prev = lastFrameNs.getOrDefault(peerKey, 0L);
                if (now - prev < 66_000_000L) return null; // ~15 fps is enough for Swing demo.
                lastFrameNs.put(peerKey, now);
                try {
                    BufferedImage image = frameToImage(args[0]);
                    if (image != null && videoCallback != null) videoCallback.onFrame(peerKey, image);
                } catch (Throwable e) {
                    // Avoid flooding log on every video frame. Only report occasionally via status.
                }
            }
            return defaultValue(method.getReturnType());
        });
        videoSinkRefs.put(peerKey, sink);
        videoSinkTrackRefs.put(peerKey, track);
        invokeByName(track, "addSink", sink);
    }

    private synchronized void detachVideoSink(String peerKey) {
        Object sink = videoSinkRefs.remove(peerKey);
        Object track = videoSinkTrackRefs.remove(peerKey);
        if (sink != null && track != null) {
            try { invokeByName(track, "removeSink", sink); } catch (Throwable ignored) {}
        }
    }

    private static Object readFieldOrGetter(Object obj, String fieldName, String getterName) {
        try { return obj.getClass().getField(fieldName).get(obj); } catch (Throwable ignored) {}
        try { return invokeByName(obj, getterName); } catch (Throwable ignored) {}
        return null;
    }

    private BufferedImage frameToImage(Object frame) throws Exception {
        Object buffer = frame.getClass().getField("buffer").get(frame);
        int rotation = frame.getClass().getField("rotation").getInt(frame);
        Object i420 = invokeByName(buffer, "toI420");
        try {
            int w = ((Number) invokeByName(i420, "getWidth")).intValue();
            int h = ((Number) invokeByName(i420, "getHeight")).intValue();
            ByteBuffer y = ((ByteBuffer) invokeByName(i420, "getDataY")).duplicate();
            ByteBuffer u = ((ByteBuffer) invokeByName(i420, "getDataU")).duplicate();
            ByteBuffer v = ((ByteBuffer) invokeByName(i420, "getDataV")).duplicate();
            int sy = ((Number) invokeByName(i420, "getStrideY")).intValue();
            int su = ((Number) invokeByName(i420, "getStrideU")).intValue();
            int sv = ((Number) invokeByName(i420, "getStrideV")).intValue();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] rgb = new int[w * h];
            int k = 0;
            for (int yy = 0; yy < h; yy++) {
                int yRow = yy * sy, uvRowU = (yy / 2) * su, uvRowV = (yy / 2) * sv;
                for (int xx = 0; xx < w; xx++) {
                    int Y = y.get(yRow + xx) & 0xff;
                    int U = u.get(uvRowU + (xx / 2)) & 0xff;
                    int V = v.get(uvRowV + (xx / 2)) & 0xff;
                    int c = Y - 16, d = U - 128, e = V - 128;
                    int r = clip((298 * c + 409 * e + 128) >> 8);
                    int g = clip((298 * c - 100 * d - 208 * e + 128) >> 8);
                    int b = clip((298 * c + 516 * d + 128) >> 8);
                    rgb[k++] = (r << 16) | (g << 8) | b;
                }
            }
            img.setRGB(0,0,w,h,rgb,0,w);
            return rotate(img, rotation);
        } finally {
            try { invokeByName(i420, "release"); } catch (Throwable ignored) {}
        }
    }

    private static BufferedImage rotate(BufferedImage src, int rotation) {
        rotation = ((rotation % 360) + 360) % 360;
        if (rotation == 0) return src;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(rotation == 180 ? w : h, rotation == 180 ? h : w, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        if (rotation == 90) { g.translate(h,0); g.rotate(Math.PI/2); }
        else if (rotation == 180) { g.translate(w,h); g.rotate(Math.PI); }
        else if (rotation == 270) { g.translate(0,w); g.rotate(-Math.PI/2); }
        g.drawImage(src,0,0,null); g.dispose(); return dst;
    }

    private static int clip(int x) { return Math.max(0, Math.min(255, x)); }

    private void loadClasses() throws Exception {
        clsFactory = Class.forName("dev.onvoid.webrtc.PeerConnectionFactory");
        clsRtcConfig = Class.forName("dev.onvoid.webrtc.RTCConfiguration");
        clsIceServer = Class.forName("dev.onvoid.webrtc.RTCIceServer");
        clsPeerObserver = Class.forName("dev.onvoid.webrtc.PeerConnectionObserver");
        clsOfferOptions = Class.forName("dev.onvoid.webrtc.RTCOfferOptions");
        clsAnswerOptions = Class.forName("dev.onvoid.webrtc.RTCAnswerOptions");
        clsCreateSdpObserver = Class.forName("dev.onvoid.webrtc.CreateSessionDescriptionObserver");
        clsSetSdpObserver = Class.forName("dev.onvoid.webrtc.SetSessionDescriptionObserver");
        clsSessionDescription = Class.forName("dev.onvoid.webrtc.RTCSessionDescription");
        clsSdpType = Class.forName("dev.onvoid.webrtc.RTCSdpType");
        clsIceCandidate = Class.forName("dev.onvoid.webrtc.RTCIceCandidate");
        clsVideoSink = Class.forName("dev.onvoid.webrtc.media.video.VideoTrackSink");
    }

    private static void setPublicFieldIfExists(Object obj, String name, Object value) {
        try { obj.getClass().getField(name).set(obj, value); } catch (Exception ignored) {}
    }

    private static Object invokeByName(Object target, String name, Object... args) throws Exception {
        Method best = null;
        Object[] converted = null;
        outer: for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] types = m.getParameterTypes();
            Object[] candidate = new Object[args.length];
            for (int i = 0; i < types.length; i++) {
                Object value = args[i];
                if (value == null) { candidate[i] = null; continue; }
                Class<?> wanted = wrap(types[i]);
                if (wanted.isAssignableFrom(value.getClass())) { candidate[i] = value; continue; }
                if (value instanceof Number n && Number.class.isAssignableFrom(wanted)) {
                    candidate[i] = convertNumber(n, wanted); continue;
                }
                continue outer;
            }
            best = m; converted = candidate; break;
        }
        if (best == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
        try { return best.invoke(target, converted); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private static Object convertNumber(Number n, Class<?> wanted) {
        if (wanted == Integer.class) return n.intValue();
        if (wanted == Long.class) return n.longValue();
        if (wanted == Double.class) return n.doubleValue();
        if (wanted == Float.class) return n.floatValue();
        if (wanted == Short.class) return n.shortValue();
        if (wanted == Byte.class) return n.byteValue();
        return n;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class; if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class; if (c == double.class) return Double.class;
        if (c == float.class) return Float.class; if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class; if (c == char.class) return Character.class;
        return c;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false; if (type == char.class) return '\0';
        if (type == byte.class) return (byte)0; if (type == short.class) return (short)0;
        if (type == int.class) return 0; if (type == long.class) return 0L;
        if (type == float.class) return 0f; if (type == double.class) return 0d;
        return null;
    }

    private void status(String s) { if (statusCallback != null) statusCallback.onStatus(s); }
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static String rootMessage(Throwable e) {
        Throwable x = e;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String m = x.getMessage();
        return m == null || m.isBlank() ? x.getClass().getSimpleName() : m;
    }

    @Override public synchronized void close() {
        Object oldScreenTrack = screenTrack, oldScreenSource = screenSource;
        screenTrack = null; screenSource = null; screenSharing = false;
        for (String key : new ArrayList<>(videoSinkRefs.keySet())) detachVideoSink(key);
        for (Peer p : new ArrayList<>(peers.values())) {
            try { if (p.audioSender != null) invokeByName(p.audioSender, "dispose"); } catch (Throwable ignored) {}
            try { if (p.videoSender != null) invokeByName(p.videoSender, "dispose"); } catch (Throwable ignored) {}
            try { invokeByName(p.pc, "close"); } catch (Throwable ignored) {}
        }
        peers.clear(); lastFrameNs.clear();
        if (oldScreenTrack != null) try { invokeByName(oldScreenTrack, "dispose"); } catch (Throwable ignored) {}
        if (oldScreenSource != null) {
            try { invokeByName(oldScreenSource, "stop"); } catch (Throwable ignored) {}
            try { invokeByName(oldScreenSource, "dispose"); } catch (Throwable ignored) {}
        }
        safeDisposeMedia(); initialized = false;
    }

    private void safeDisposeMedia() {
        try { if (videoSource != null) invokeByName(videoSource,"stop"); } catch (Throwable ignored) {}
        try { if (videoTrack != null) invokeByName(videoTrack,"dispose"); } catch (Throwable ignored) {}
        try { if (audioTrack != null) invokeByName(audioTrack,"dispose"); } catch (Throwable ignored) {}
        try { if (videoSource != null) invokeByName(videoSource,"dispose"); } catch (Throwable ignored) {}
        try { if (audioSource != null) invokeByName(audioSource,"dispose"); } catch (Throwable ignored) {}
        try { if (factory != null) invokeByName(factory,"dispose"); } catch (Throwable ignored) {}
        factory=null;audioSource=null;audioTrack=null;videoSource=null;videoTrack=null;
    }

    public static final class ScreenSource {
        private final long id;
        private final String title;
        ScreenSource(long id, String title) { this.id = id; this.title = title; }
        public long id() { return id; }
        public String title() { return title; }
        @Override public String toString() { return title; }
    }

    private static final class Peer {
        final String email;
        final Object pc;
        final Object audioSender;
        volatile Object videoSender;
        boolean remoteDescriptionSet;
        final List<IceData> pendingIce = new CopyOnWriteArrayList<>();
        Peer(String email,Object pc,Object audioSender,Object videoSender){this.email=email;this.pc=pc;this.audioSender=audioSender;this.videoSender=videoSender;}
    }
    private record IceData(String mid,int index,String candidate) {}
}
