/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.residencia;

import com.app.dto.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(name = "LoginServlet", urlPatterns = {"/api/login"})
public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();
        Gson gson = new Gson();

        try {
            // 1. Ler JSON
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = request.getReader().readLine()) != null) sb.append(s);
            UserLogin req = gson.fromJson(sb.toString(), UserLogin.class);

            String action = req.action != null ? req.action : "LOGIN";

            try (Connection conn = DatabaseManager.getConnection()) {
                
                if ("LOGIN".equals(action)) {
                    // 2. Validação Básica
                    if (!DatabaseManager.isValid(req.email, "EMAIL")) {
                        response.setStatus(400);
                        out.print("{\"success\": false, \"message\": \"Email inválido.\"}");
                        return;
                    }

                    // Faz o JOIN para pegar os dados do usuário e da casa
                    String sql = "SELECT u.*, h.name as house_name, h.invite_code " +
                                 "FROM users u " +
                                 "LEFT JOIN houses h ON u.house_id = h.id " +
                                 "WHERE u.email = ? AND u.password_hash = ?";
                    
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, req.email);
                    stmt.setString(2, req.password);

                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        
                        // === VERIFICAÇÃO DO PROTOCOLO FANTASMA ===
                        boolean isActive = rs.getBoolean("active");
                        
                        if (!isActive) {
                            // CONTA DESATIVADA! Manda aviso para o Front-end perguntar se quer voltar.
                            JsonObject ghostResponse = new JsonObject();
                            ghostResponse.addProperty("success", false);
                            ghostResponse.addProperty("requireReactivation", true);
                            ghostResponse.addProperty("user_id", rs.getInt("id"));
                            ghostResponse.addProperty("message", "Sua conta encontra-se no MODO FANTASMA.");
                            out.print(gson.toJson(ghostResponse));
                            return; // Interrompe o login aqui
                        }

                        // === LOGIN NORMAL (SUCESSO) ===
                        JsonObject jsonResponse = new JsonObject();
                        jsonResponse.addProperty("success", true);
                        jsonResponse.addProperty("message", "Acesso Autorizado");
                        
                        JsonObject user = new JsonObject();
                        user.addProperty("id", rs.getInt("id"));
                        user.addProperty("name", rs.getString("name"));
                        user.addProperty("email", rs.getString("email"));
                        user.addProperty("role", rs.getString("role"));
                        
                        // Dados da Casa
                        user.addProperty("house_id", rs.getObject("house_id") != null ? rs.getInt("house_id") : null);
                        user.addProperty("house_name", rs.getString("house_name"));
                        user.addProperty("invite_code", rs.getString("invite_code"));
                        
                        jsonResponse.add("user", user);
                        out.print(gson.toJson(jsonResponse));
                        
                    } else {
                        // ERRO: Senha ou Email incorretos
                        response.setStatus(401);
                        out.print("{\"success\": false, \"message\": \"Credenciais Inválidas\"}");
                    }
                    
                } else if ("REACTIVATE".equals(action)) {
                    // === O USUÁRIO CONFIRMOU QUE QUER REVIVER A CONTA ===
                    String sqlReactivate = "UPDATE users SET active = TRUE WHERE id = ?";
                    PreparedStatement stmtReactivate = conn.prepareStatement(sqlReactivate);
                    stmtReactivate.setInt(1, req.user_id);
                    int rows = stmtReactivate.executeUpdate();
                    
                    if (rows > 0) {
                        out.print("{\"success\": true, \"message\": \"Conexão com a Matrix restabelecida! Faça login novamente.\"}");
                    } else {
                        out.print("{\"success\": false, \"message\": \"Falha ao tentar reativar a conta.\"}");
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            out.print("{\"success\": false, \"message\": \"Erro no Servidor: " + e.getMessage() + "\"}");
        }
    }

    // Classe auxiliar atualizada para receber a ação de reativação
    private class UserLogin {
        String action;
        int user_id;
        String email;
        String password;
    }
}