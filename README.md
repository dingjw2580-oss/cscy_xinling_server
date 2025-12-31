# WebRTC 独立信令服务器

## 概述

这是一个独立的WebRTC信令服务器，用于转发客户端和服务端之间的SDP和ICE candidate消息。

## 架构

```
客户端 <--WebSocket--> 信令服务器 <--WebSocket--> 服务端
```

- **信令服务器**：部署在测试服务器上，负责转发消息
- **客户端**：Android应用，连接信令服务器
- **服务端**：Android设备，连接信令服务器

## 编译

```bash
cd signaling-server
mvn clean package
```

编译后会在 `target/` 目录生成 `webrtc-signaling-server-1.0.0.jar`

## 运行

### 默认端口（10000）

```bash
java -jar target/webrtc-signaling-server-1.0.0.jar
```

### 自定义端口

```bash
java -jar target/webrtc-signaling-server-1.0.0.jar --port=8080
```

### 查看帮助

```bash
java -jar target/webrtc-signaling-server-1.0.0.jar --help
```

## 部署

### 1. 上传JAR文件到服务器

```bash
scp target/webrtc-signaling-server-1.0.0.jar user@your-server.com:/opt/signaling/
```

### 2. 在服务器上运行

```bash
# SSH到服务器
ssh user@your-server.com

# 进入目录
cd /opt/signaling

# 运行（后台运行）
nohup java -jar webrtc-signaling-server-1.0.0.jar --port=10000 > signaling.log 2>&1 &

# 查看日志
tail -f signaling.log
```

### 3. 使用systemd管理（推荐）

创建 `/etc/systemd/system/signaling-server.service`：

```ini
[Unit]
Description=WebRTC Signaling Server
After=network.target

[Service]
Type=simple
User=your-user
WorkingDirectory=/opt/signaling
ExecStart=/usr/bin/java -jar /opt/signaling/webrtc-signaling-server-1.0.0.jar --port=10000
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable signaling-server
sudo systemctl start signaling-server
sudo systemctl status signaling-server
```

## 防火墙配置

确保开放信令服务器端口：

```bash
# Ubuntu/Debian
sudo ufw allow 10000/tcp

# CentOS/RHEL
sudo firewall-cmd --permanent --add-port=10000/tcp
sudo firewall-cmd --reload
```

## 消息协议

### 连接确认

服务器发送给客户端/服务端：

```json
{
  "type": "connected",
  "clientId": "client-1",
  "role": "client"  // 或 "server"
}
```

### Offer消息

客户端 -> 信令服务器 -> 服务端：

```json
{
  "type": "offer",
  "sdp": "v=0\r\no=- ...",
  "fromClientId": "client-1"  // 信令服务器添加
}
```

### Answer消息

服务端 -> 信令服务器 -> 客户端：

```json
{
  "type": "answer",
  "sdp": "v=0\r\no=- ...",
  "toClientId": "client-1"  // 可选，如果只有一个客户端可以省略
}
```

### ICE Candidate消息

双向转发：

```json
{
  "type": "candidate",
  "candidate": "candidate:...",
  "sdpMid": "0",
  "sdpMLineIndex": 0,
  "toClientId": "client-1"  // 可选
}
```

## 日志

日志输出到控制台，包含：
- 连接/断开连接事件
- 消息转发记录
- 错误信息

日志级别可以通过系统属性配置：

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar webrtc-signaling-server-1.0.0.jar
```

## 监控

### 检查服务状态

```bash
# 查看进程
ps aux | grep signaling-server

# 查看端口监听
netstat -tlnp | grep 10000
# 或
ss -tlnp | grep 10000
```

### 查看连接数

```bash
# 查看WebSocket连接
netstat -an | grep 10000 | grep ESTABLISHED | wc -l
```

## 故障排查

### 1. 服务无法启动

- 检查端口是否被占用：`lsof -i :10000`
- 检查Java版本：`java -version`（需要Java 11+）
- 查看日志文件

### 2. 客户端无法连接

- 检查防火墙规则
- 检查服务器IP和端口是否正确
- 检查网络连通性：`telnet server-ip 10000`

### 3. 消息未转发

- 检查服务端和客户端是否都已连接
- 查看服务器日志
- 检查消息格式是否正确

## 性能优化

### JVM参数

```bash
java -Xms512m -Xmx1024m -jar webrtc-signaling-server-1.0.0.jar
```

### 连接数限制

当前实现支持：
- 1个服务端连接
- 多个客户端连接（理论上无限制，但建议根据服务器性能设置上限）

## 安全建议

1. **使用HTTPS/WSS**：生产环境建议使用WSS（WebSocket Secure）
2. **认证机制**：可以添加Token认证
3. **限流**：防止恶意连接
4. **IP白名单**：限制允许连接的IP

## 扩展功能

可以添加的功能：
- 多房间支持
- 用户认证
- 消息加密
- 连接数限制
- 统计和监控

