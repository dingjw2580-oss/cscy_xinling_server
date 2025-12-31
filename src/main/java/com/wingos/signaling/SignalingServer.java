package com.wingos.signaling;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebRTC独立信令服务器
 * 
 * 功能：
 * 1. 接收客户端和服务端的WebSocket连接
 * 2. 转发SDP和ICE candidate消息
 * 3. 支持多对一连接（多个客户端连接到一个服务端）
 * 
 * 部署说明：
 * - 部署到测试服务器
 * - 默认端口：10000
 * - 可通过命令行参数修改端口：java -jar signaling-server.jar --port=10000
 */
public class SignalingServer {
    private static final Logger logger = LoggerFactory.getLogger(SignalingServer.class);
    private static final int DEFAULT_PORT = 10000;
    
    private WebSocketServer webSocketServer;
    private final Map<String, WebSocket> clients = new ConcurrentHashMap<>(); // 客户端连接（多个）
    private final Map<WebSocket, String> connectionToId = new ConcurrentHashMap<>(); // 连接 -> ID映射
    private WebSocket serverConnection = null; // 服务端连接（单个）
    private final AtomicInteger clientIdCounter = new AtomicInteger(0);
    
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
    
        // 1. 优先读取环境变量 PORT (云平台标准)
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                port = Integer.parseInt(envPort);
            } catch (NumberFormatException e) {
                logger.error("Invalid PORT env var: " + envPort);
            }
        } 
        // 2. 如果没有环境变量，再读取命令行参数
        else {
            for (String arg : args) {
                if (arg.startsWith("--port=")) {
                    try {
                        port = Integer.parseInt(arg.substring(7));
                    } catch (NumberFormatException e) {
                        logger.error("Invalid port number: " + arg);
                        System.exit(1);
                    }
                }
            }
        }
        // 解析命令行参数
        // for (String arg : args) {
        //     if (arg.startsWith("--port=")) {
        //         try {
        //             port = Integer.parseInt(arg.substring(7));
        //         } catch (NumberFormatException e) {
        //             logger.error("Invalid port number: " + arg);
        //             System.exit(1);
        //         }
        //     } else if (arg.equals("--help") || arg.equals("-h")) {
        //         System.out.println("Usage: java -jar signaling-server.jar [--port=PORT]");
        //         System.out.println("Default port: " + DEFAULT_PORT);
        //         System.exit(0);
        //     }
        // }
        
        SignalingServer server = new SignalingServer();
        server.start(port);
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down signaling server...");
            server.stop();
        }));
    }
    
    public void start(int port) {
        try {
            InetSocketAddress address = new InetSocketAddress(port);
            webSocketServer = new WebSocketServer(address) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    String clientId = generateClientId();
                    connectionToId.put(conn, clientId);
                    
                    // 判断是服务端还是客户端
                    // 可以通过查询参数或自定义Header来区分
                    String query = handshake.getResourceDescriptor();
                    boolean isServer = query != null && query.contains("role=server");
                    
                    if (isServer) {
                        // 服务端连接
                        if (serverConnection != null && !serverConnection.isClosed()) {
                            logger.warn("Server connection already exists, closing old connection");
                            serverConnection.close();
                            connectionToId.remove(serverConnection);
                        }
                        serverConnection = conn;
                        logger.info("Server connected: {} ({})", conn.getRemoteSocketAddress(), clientId);
                    } else {
                        // 客户端连接
                        clients.put(clientId, conn);
                        logger.info("Client connected: {} ({})", conn.getRemoteSocketAddress(), clientId);
                    }
                    
                    // 发送连接确认
                    sendConnectionAck(conn, clientId, isServer);
                }
                
                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    String clientId = connectionToId.remove(conn);
                    logger.info("Connection closed: {} (code={}, reason={})", clientId, code, reason);
                    
                    if (conn == serverConnection) {
                        serverConnection = null;
                        logger.info("Server disconnected");
                    } else if (clientId != null) {
                        clients.remove(clientId);
                        logger.info("Client disconnected: {}", clientId);
                    }
                }
                
                @Override
                public void onMessage(WebSocket conn, String message) {
                    String clientId = connectionToId.get(conn);
                    try {
                        handleMessage(conn, message, clientId);
                    } catch (Exception e) {
                        logger.error("Error handling message from {}: {}", clientId, message, e);
                    }
                }
                
                @Override
                public void onError(WebSocket conn, Exception ex) {
                    String clientId = conn != null ? connectionToId.get(conn) : "unknown";
                    logger.error("WebSocket error for {}: {}", clientId, ex.getMessage(), ex);
                }
                
                @Override
                public void onStart() {
                    logger.info("========================================");
                    logger.info("WebRTC Signaling Server Started");
                    logger.info("Port: {}", port);
                    logger.info("Waiting for connections...");
                    logger.info("========================================");
                }
            };
            
            webSocketServer.setConnectionLostTimeout(60);
            webSocketServer.start();
            
        } catch (Exception e) {
            logger.error("Failed to start signaling server", e);
            System.exit(1);
        }
    }
    
    public void stop() {
        try {
            if (webSocketServer != null) {
                webSocketServer.stop();
            }
        } catch (Exception e) {
            logger.error("Error stopping server", e);
        }
    }
    
    private String generateClientId() {
        return "client-" + clientIdCounter.incrementAndGet();
    }
    
    private void sendConnectionAck(WebSocket conn, String clientId, boolean isServer) {
        try {
            JSONObject ack = new JSONObject();
            ack.put("type", "connected");
            ack.put("clientId", clientId);
            ack.put("role", isServer ? "server" : "client");
            conn.send(ack.toString());
        } catch (Exception e) {
            logger.error("Failed to send connection ACK", e);
        }
    }
    
    private void handleMessage(WebSocket conn, String message, String clientId) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "");
            
            boolean isServer = (conn == serverConnection);
            
            switch (type) {
                case "offer":
                    handleOffer(conn, json, clientId, isServer);
                    break;
                    
                case "answer":
                    handleAnswer(conn, json, clientId, isServer);
                    break;
                    
                case "candidate":
                    handleCandidate(conn, json, clientId, isServer);
                    break;
                    
                default:
                    logger.warn("Unknown message type: {} from {}", type, clientId);
                    break;
            }
        } catch (JSONException e) {
            logger.error("Invalid JSON message from {}: {}", clientId, message, e);
        }
    }
    
    /**
     * 处理Offer消息
     * 客户端 -> 信令服务器 -> 服务端
     */
    private void handleOffer(WebSocket conn, JSONObject json, String clientId, boolean isServer) {
        if (isServer) {
            logger.warn("Server sent offer (unexpected), ignoring");
            return;
        }
        
        if (serverConnection == null || serverConnection.isClosed()) {
            logger.warn("No server connection available, cannot forward offer from {}", clientId);
            sendError(conn, "No server available");
            return;
        }
        
        try {
            // 添加客户端ID到消息中，以便服务端知道消息来源
            json.put("fromClientId", clientId);
            String message = json.toString();
            
            serverConnection.send(message);
            logger.info("Forwarded offer from client {} to server", clientId);
        } catch (Exception e) {
            logger.error("Failed to forward offer from {}", clientId, e);
            sendError(conn, "Failed to forward offer");
        }
    }
    
    /**
     * 处理Answer消息
     * 服务端 -> 信令服务器 -> 客户端
     */
    private void handleAnswer(WebSocket conn, JSONObject json, String clientId, boolean isServer) {
        if (!isServer) {
            logger.warn("Client sent answer (unexpected), ignoring");
            return;
        }
        
        // 获取目标客户端ID
        String targetClientId = json.optString("toClientId", null);
        
        if (targetClientId != null) {
            // 转发给指定客户端
            WebSocket targetClient = clients.get(targetClientId);
            if (targetClient != null && !targetClient.isClosed()) {
                try {
                    // 移除目标客户端ID，只发送标准格式
                    JSONObject answer = new JSONObject();
                    answer.put("type", "answer");
                    answer.put("sdp", json.getString("sdp"));
                    targetClient.send(answer.toString());
                    logger.info("Forwarded answer from server to client {}", targetClientId);
                } catch (Exception e) {
                    logger.error("Failed to forward answer to client {}", targetClientId, e);
                }
            } else {
                logger.warn("Target client {} not found or disconnected", targetClientId);
            }
        } else {
            // 广播给所有客户端（如果只有一个客户端，这是合理的）
            if (clients.size() == 1) {
                WebSocket client = clients.values().iterator().next();
                if (client != null && !client.isClosed()) {
                    try {
                        JSONObject answer = new JSONObject();
                        answer.put("type", "answer");
                        answer.put("sdp", json.getString("sdp"));
                        client.send(answer.toString());
                        logger.info("Broadcasted answer from server to single client");
                    } catch (Exception e) {
                        logger.error("Failed to broadcast answer", e);
                    }
                }
            } else {
                logger.warn("Multiple clients connected but no targetClientId specified in answer");
            }
        }
    }
    
    /**
     * 处理ICE Candidate消息
     * 双向转发：客户端 <-> 信令服务器 <-> 服务端
     */
    private void handleCandidate(WebSocket conn, JSONObject json, String clientId, boolean isServer) {
        try {
            if (isServer) {
                // 服务端 -> 客户端
                String targetClientId = json.optString("toClientId", null);
                
                if (targetClientId != null) {
                    WebSocket targetClient = clients.get(targetClientId);
                    if (targetClient != null && !targetClient.isClosed()) {
                        JSONObject candidate = new JSONObject();
                        candidate.put("type", "candidate");
                        candidate.put("candidate", json.getString("candidate"));
                        candidate.put("sdpMid", json.getString("sdpMid"));
                        candidate.put("sdpMLineIndex", json.getInt("sdpMLineIndex"));
                        targetClient.send(candidate.toString());
                        logger.debug("Forwarded ICE candidate from server to client {}", targetClientId);
                    }
                } else {
                    // 广播给所有客户端
                    JSONObject candidate = new JSONObject();
                    candidate.put("type", "candidate");
                    candidate.put("candidate", json.getString("candidate"));
                    candidate.put("sdpMid", json.getString("sdpMid"));
                    candidate.put("sdpMLineIndex", json.getInt("sdpMLineIndex"));
                    
                    for (WebSocket client : clients.values()) {
                        if (client != null && !client.isClosed()) {
                            client.send(candidate.toString());
                        }
                    }
                    logger.debug("Broadcasted ICE candidate from server to all clients");
                }
            } else {
                // 客户端 -> 服务端
                if (serverConnection == null || serverConnection.isClosed()) {
                    logger.warn("No server connection available, cannot forward candidate from {}", clientId);
                    return;
                }
                
                json.put("fromClientId", clientId);
                serverConnection.send(json.toString());
                logger.debug("Forwarded ICE candidate from client {} to server", clientId);
            }
        } catch (Exception e) {
            logger.error("Failed to forward ICE candidate", e);
        }
    }
    
    private void sendError(WebSocket conn, String error) {
        try {
            JSONObject errorMsg = new JSONObject();
            errorMsg.put("type", "error");
            errorMsg.put("message", error);
            conn.send(errorMsg.toString());
        } catch (Exception e) {
            logger.error("Failed to send error message", e);
        }
    }
}

