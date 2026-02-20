package com.app.residencia;

import com.app.dto.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

// MUDANÇA DE SEGURANÇA: Agora usamos o userId na URL, e não mais o nome!
@ServerEndpoint("/api/chat/{houseId}/{userId}")
public class ChatWebSocket {

    private static final ConcurrentHashMap<String, Map<Session, String>> houseSessions = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    @OnOpen
    public void onOpen(Session session, @PathParam("houseId") String houseId, @PathParam("userId") String userId) {
        
        // 1. Descobre o nome verdadeiro do usuário pelo ID
        String userName = buscarNomeUsuario(userId);
        if (userName == null || !enviarHistorico(session, houseId, userId)) {
            cortarConexao(session);
            return;
        }

        // 2. Regista a sessão usando o nome real
        houseSessions.computeIfAbsent(houseId, k -> new ConcurrentHashMap<>()).put(session, userName);
        System.out.println("[SYS] Operador Online: " + userName + " | Casa: " + houseId);

        broadcastOnlineUsers(houseId);
    }

    @OnMessage
    public void onMessage(String payload, Session session, @PathParam("houseId") String houseId, @PathParam("userId") String userId) {

        // === COMANDO DE LEITURA (MARK_READ) ===
        // O JavaScript avisa o Java: "Eu já li até a mensagem X"
        if (payload.startsWith("MARK_READ:")) {
            int lastReadId = Integer.parseInt(payload.split(":")[1]);
            atualizarUltimaLida(userId, lastReadId);
            return;
        }

        // === HEARTBEAT (PULSO DE VIDA) ===
        if ("SYS_PING".equals(payload)) {
            if (!verificarBancoAtivo()) {
                cortarConexao(session);
            }
            return; 
        }

        // === MENSAGEM NORMAL DO CHAT ===
        String userName = houseSessions.get(houseId).get(session);
        int msgId = salvarMensagemNoBanco(houseId, userName, payload);
        
        if (msgId == -1) { // Se o ID voltar -1, o banco caiu
            cortarConexao(session);
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", "MESSAGE");
        json.addProperty("id", msgId); // Agora a mensagem tem um ID único
        json.addProperty("sender", userName);
        json.addProperty("message", payload);

        broadcastToHouse(houseId, gson.toJson(json));
    }

    @OnClose
    public void onClose(Session session, @PathParam("houseId") String houseId) {
        Map<Session, String> sessions = houseSessions.get(houseId);
        if (sessions != null) {
            sessions.remove(session);
            broadcastOnlineUsers(houseId);
        }
    }

    // ==========================================
    // FUNÇÕES DE BANCO DE DADOS (BLINDADAS)
    // ==========================================

    private boolean enviarHistorico(Session session, String houseId, String userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            
            // 1. Descobre onde o usuário parou de ler
            int lastReadId = 0;
            String sqlUser = "SELECT last_read_msg_id FROM users WHERE id = ?";
            PreparedStatement stmtUser = conn.prepareStatement(sqlUser);
            stmtUser.setInt(1, Integer.parseInt(userId));
            ResultSet rsUser = stmtUser.executeQuery();
            if (rsUser.next()) {
                lastReadId = rsUser.getInt("last_read_msg_id");
            }

            // 2. Puxa as últimas 50 mensagens incluindo o ID
            String sqlChat = "SELECT id, sender_name, message FROM chat_messages WHERE house_id = ? ORDER BY id DESC LIMIT 50";
            PreparedStatement stmtChat = conn.prepareStatement(sqlChat);
            stmtChat.setInt(1, Integer.parseInt(houseId));
            ResultSet rsChat = stmtChat.executeQuery();

            List<JsonObject> tempHistory = new ArrayList<>();
            while (rsChat.next()) {
                JsonObject msg = new JsonObject();
                msg.addProperty("id", rsChat.getInt("id"));
                msg.addProperty("sender", rsChat.getString("sender_name"));
                msg.addProperty("message", rsChat.getString("message"));
                tempHistory.add(msg);
            }
            Collections.reverse(tempHistory);

            JsonArray historyArray = new JsonArray();
            for (JsonObject o : tempHistory) {
                historyArray.add(o);
            }

            // 3. Monta o pacote final com o histórico e a marcação de leitura
            JsonObject json = new JsonObject();
            json.addProperty("type", "HISTORY");
            json.addProperty("lastReadId", lastReadId);
            json.add("messages", historyArray);

            session.getBasicRemote().sendText(gson.toJson(json));
            return true;
        } catch (Exception e) {
            System.err.println("[ERRO CRÍTICO] Falha ao ler o Banco no Chat!");
            return false;
        }
    }

    // Agora retorna o ID da mensagem salva (ou -1 se falhar)
    private int salvarMensagemNoBanco(String houseId, String userName, String encryptedMessage) {
        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "INSERT INTO chat_messages (house_id, sender_name, message) VALUES (?, ?, ?)";
            // Statement.RETURN_GENERATED_KEYS avisa o MySQL para devolver o ID gerado
            PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            stmt.setInt(1, Integer.parseInt(houseId));
            stmt.setString(2, userName);
            stmt.setString(3, encryptedMessage);
            stmt.executeUpdate();
            
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1); // Retorna o ID novinho em folha
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void atualizarUltimaLida(String userId, int lastReadId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "UPDATE users SET last_read_msg_id = ? WHERE id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, lastReadId);
            stmt.setInt(2, Integer.parseInt(userId));
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao atualizar marcação de leitura.");
        }
    }

    private String buscarNomeUsuario(String userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "SELECT name FROM users WHERE id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, Integer.parseInt(userId));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("name").split(" ")[0]; // Pega o primeiro nome
            }
        } catch (Exception e) {}
        return null;
    }

    // ... (As funções broadcastOnlineUsers, broadcastToHouse, cortarConexao e verificarBancoAtivo continuam iguaizinhas, omitidas para economizar espaço) ...
    
    private void broadcastOnlineUsers(String houseId) {
        Map<Session, String> sessions = houseSessions.get(houseId);
        if (sessions == null) return;
        List<String> onlineUsers = new ArrayList<>(new java.util.HashSet<>(sessions.values()));
        JsonObject json = new JsonObject();
        json.addProperty("type", "USERS");
        json.add("list", gson.toJsonTree(onlineUsers));
        broadcastToHouse(houseId, gson.toJson(json));
    }

    private void broadcastToHouse(String houseId, String payload) {
        Map<Session, String> sessions = houseSessions.get(houseId);
        if (sessions != null) {
            for (Session s : sessions.keySet()) {
                if (s.isOpen()) {
                    try { s.getBasicRemote().sendText(payload); } catch (IOException e) { e.printStackTrace(); }
                }
            }
        }
    }

    private void cortarConexao(Session session) {
        try { if (session.isOpen()) session.close(); } catch (IOException e) { e.printStackTrace(); }
    }

    private boolean verificarBancoAtivo() {
        try (Connection conn = DatabaseManager.getConnection()) {
            java.sql.Statement stmt = conn.createStatement();
            stmt.executeQuery("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}