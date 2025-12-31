package com.wingos.signaling;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * WebSocket客户端封装
 * 用于服务端和客户端连接到独立信令服务器
 */
public class SignalingWebSocketClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(SignalingWebSocketClient.class);
    
    private final MessageHandler messageHandler;
    private volatile boolean connected = false;
    
    public interface MessageHandler {
        void onConnected();
        void onDisconnected();
        void onOffer(String sdp, String fromClientId);
        void onAnswer(String sdp);
        void onIceCandidate(String candidate, String sdpMid, int sdpMLineIndex, String fromClientId);
        void onError(String error);
    }
    
    public SignalingWebSocketClient(URI serverUri, MessageHandler messageHandler) {
        super(serverUri);
        this.messageHandler = messageHandler;
        setConnectionLostTimeout(60);
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.info("Connected to signaling server: {}", getURI());
        connected = true;
        if (messageHandler != null) {
            messageHandler.onConnected();
        }
    }
    
    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "");
            
            switch (type) {
                case "connected":
                    logger.info("Connection acknowledged by signaling server");
                    break;
                    
                case "offer":
                    String offerSdp = json.getString("sdp");
                    String fromClientId = json.optString("fromClientId", null);
                    if (messageHandler != null) {
                        messageHandler.onOffer(offerSdp, fromClientId);
                    }
                    break;
                    
                case "answer":
                    String answerSdp = json.getString("sdp");
                    if (messageHandler != null) {
                        messageHandler.onAnswer(answerSdp);
                    }
                    break;
                    
                case "candidate":
                    String candidate = json.getString("candidate");
                    String sdpMid = json.getString("sdpMid");
                    int sdpMLineIndex = json.getInt("sdpMLineIndex");
                    String fromId = json.optString("fromClientId", null);
                    if (messageHandler != null) {
                        messageHandler.onIceCandidate(candidate, sdpMid, sdpMLineIndex, fromId);
                    }
                    break;
                    
                case "error":
                    String error = json.optString("message", "Unknown error");
                    logger.error("Error from signaling server: {}", error);
                    if (messageHandler != null) {
                        messageHandler.onError(error);
                    }
                    break;
                    
                default:
                    logger.warn("Unknown message type: {}", type);
                    break;
            }
        } catch (Exception e) {
            logger.error("Error parsing message: {}", message, e);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("Disconnected from signaling server: code={}, reason={}", code, reason);
        connected = false;
        if (messageHandler != null) {
            messageHandler.onDisconnected();
        }
    }
    
    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket error", ex);
        if (messageHandler != null) {
            messageHandler.onError(ex.getMessage());
        }
    }
    
    public boolean isConnected() {
        return connected && !isClosed();
    }
    
    public void sendOffer(String sdp) {
        sendMessage("offer", sdp, null);
    }
    
    public void sendAnswer(String sdp, String toClientId) {
        sendMessage("answer", sdp, toClientId);
    }
    
    public void sendIceCandidate(String candidate, String sdpMid, int sdpMLineIndex, String toClientId) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "candidate");
            json.put("candidate", candidate);
            json.put("sdpMid", sdpMid);
            json.put("sdpMLineIndex", sdpMLineIndex);
            if (toClientId != null) {
                json.put("toClientId", toClientId);
            }
            send(json.toString());
        } catch (Exception e) {
            logger.error("Failed to send ICE candidate", e);
        }
    }
    
    private void sendMessage(String type, String sdp, String toClientId) {
        if (!isConnected()) {
            logger.warn("Not connected, cannot send {}", type);
            return;
        }
        
        try {
            JSONObject json = new JSONObject();
            json.put("type", type);
            json.put("sdp", sdp);
            if (toClientId != null) {
                json.put("toClientId", toClientId);
            }
            send(json.toString());
            logger.debug("Sent {} to signaling server", type);
        } catch (Exception e) {
            logger.error("Failed to send {}", type, e);
        }
    }
}

