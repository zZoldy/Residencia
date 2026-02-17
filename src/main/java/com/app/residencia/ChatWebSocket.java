package com.app.residencia;

import com.app.dto.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

@ServerEndpoint("/api/chat/{houseId}/{userName}")
public class ChatWebSocket {

    // Guarda as conexões assim: ID da Casa -> (Sessão do Navegador -> Nome do Usuário)
    private static final ConcurrentHashMap<String, Map<Session, String>> houseSessions = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    @OnOpen
    public void onOpen(Session session, @PathParam("houseId") String houseId, @PathParam("userName") String userName) {
        // Regista o usuário na sala
        houseSessions.computeIfAbsent(houseId, k -> new ConcurrentHashMap<>()).put(session, userName);
        
        System.out.println("[SYS] Operador Online: " + userName + " | Casa: " + houseId);
        
        // 1. Envia o histórico gravado no banco APENAS para quem acabou de entrar
        enviarHistorico(session, houseId);
        
        // 2. Avisa a casa inteira quem está online agora
        broadcastOnlineUsers(houseId);
    }

    @OnMessage
    public void onMessage(String encryptedMessage, Session session, @PathParam("houseId") String houseId, @PathParam("userName") String userName) {
        // Grava no banco
        salvarMensagemNoBanco(houseId, userName, encryptedMessage);

        // Monta o pacote do tipo MENSAGEM
        JsonObject json = new JsonObject();
        json.addProperty("type", "MESSAGE");
        json.addProperty("sender", userName);
        json.addProperty("message", encryptedMessage);

        // Dispara para todos
        broadcastToHouse(houseId, gson.toJson(json));
    }

    @OnClose
    public void onClose(Session session, @PathParam("houseId") String houseId) {
        Map<Session, String> sessions = houseSessions.get(houseId);
        if (sessions != null) {
            sessions.remove(session); // Remove quem saiu
            broadcastOnlineUsers(houseId); // Atualiza a lista de todos
        }
    }

    // ==========================================
    // FUNÇÕES INTERNAS DE DISTRIBUIÇÃO
    // ==========================================

    private void broadcastOnlineUsers(String houseId) {
        Map<Session, String> sessions = houseSessions.get(houseId);
        if (sessions == null) return;

        // Usa HashSet para não duplicar o nome se o morador abrir 2 abas no PC
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
                    try {
                        s.getBasicRemote().sendText(payload);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void enviarHistorico(Session session, String houseId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            // Pega as últimas 50 mensagens
            String sql = "SELECT sender_name, message FROM chat_messages WHERE house_id = ? ORDER BY id DESC LIMIT 50";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, Integer.parseInt(houseId));
            ResultSet rs = stmt.executeQuery();
            
            List<JsonObject> tempHistory = new ArrayList<>();
            while (rs.next()) {
                JsonObject msg = new JsonObject();
                msg.addProperty("sender", rs.getString("sender_name"));
                msg.addProperty("message", rs.getString("message"));
                tempHistory.add(msg);
            }
            
            // Inverte a lista para a mais velha ficar no topo e a mais nova em baixo
            Collections.reverse(tempHistory);
            
            JsonArray historyArray = new JsonArray();
            for (JsonObject o : tempHistory) historyArray.add(o);

            JsonObject json = new JsonObject();
            json.addProperty("type", "HISTORY");
            json.add("messages", historyArray);

            session.getBasicRemote().sendText(gson.toJson(json));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void salvarMensagemNoBanco(String houseId, String userName, String encryptedMessage) {
        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "INSERT INTO chat_messages (house_id, sender_name, message) VALUES (?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, Integer.parseInt(houseId));
            stmt.setString(2, userName);
            stmt.setString(3, encryptedMessage);
            stmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}