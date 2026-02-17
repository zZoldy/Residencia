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
import java.sql.*;
import java.util.Random;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(name = "RegisterServlet", urlPatterns = {"/api/register"})
public class RegisterServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();
        Gson gson = new Gson();

        try {
            // 1. LER O PACOTE COMPLETO (USER + HOUSE)
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = request.getReader().readLine()) != null) {
                sb.append(s);
            }
            FullRegistrationData data = gson.fromJson(sb.toString(), FullRegistrationData.class);

            // 2. VALIDAÇÕES BÁSICAS (Sem tocar no banco ainda)
            if (!DatabaseManager.isValid(data.email, "EMAIL")
                    || !DatabaseManager.isValid(data.password, "SENHA")
                    || !DatabaseManager.isValid(data.name, "TEXTO")) {
                response.setStatus(400);
                out.print("{\"success\": false, \"message\": \"Dados do usuário inválidos.\"}");
                return;
            }

            // 3. ABRE A CONEXÃO E A TRANSAÇÃO
            try (Connection conn = DatabaseManager.getConnection()) {

                // --- INÍCIO DA TRANSAÇÃO (O Pulo do Gato) ---
                conn.setAutoCommit(false); // Desliga o salvamento automático

                try {
                    // A. Verifica se email já existe
                    String sqlCheck = "SELECT id, active FROM users WHERE email = ? OR email = CONCAT('del_', id, '_', ?)";
                    PreparedStatement checkEmail = conn.prepareStatement(sqlCheck);
                    checkEmail.setString(1, data.email);
                    checkEmail.setString(2, data.email);
                    ResultSet rsEmail = checkEmail.executeQuery();

                    Integer ghostId = null;

                    if (rsEmail.next()) {
                        if (rsEmail.getBoolean("active")) {
                            throw new Exception("Email já cadastrado na Matrix.");
                        } else {
                            // Encontramos o fantasma! Salvamos o ID dele para ressuscitá-lo.
                            ghostId = rsEmail.getInt("id");
                        }
                    }

                    // B. Lógica da Casa (Descobrir o ID da casa ANTES ou DEPOIS de criar o usuário)
                    Integer houseId = null;
                    String finalHouseName = "";
                    String finalRole = "MEMBER";
                    String generatedInvite = null;

                    if ("JOIN".equals(data.houseAction)) {
                        // --- ENTRAR EM CASA EXISTENTE ---
                        PreparedStatement stmtCheckHouse = conn.prepareStatement("SELECT id, name FROM houses WHERE invite_code = ?");
                        stmtCheckHouse.setString(1, data.houseData);
                        ResultSet rsHouse = stmtCheckHouse.executeQuery();

                        if (rsHouse.next()) {
                            houseId = rsHouse.getInt("id");
                            finalHouseName = rsHouse.getString("name");
                        } else {
                            throw new Exception("Código de convite inválido! A operação foi cancelada.");
                        }

                    } else if ("CREATE".equals(data.houseAction)) {
                        // --- CRIAR NOVA CASA ---
                        if (!DatabaseManager.isValid(data.houseData, "TEXTO")) {
                            throw new Exception("Nome da casa inválido.");
                        }

                        generatedInvite = "#" + (1000 + new Random().nextInt(9000));
                        PreparedStatement stmtNewHouse = conn.prepareStatement("INSERT INTO houses (name, invite_code) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
                        stmtNewHouse.setString(1, data.houseData);
                        stmtNewHouse.setString(2, generatedInvite);
                        stmtNewHouse.executeUpdate();

                        ResultSet rsNewHouse = stmtNewHouse.getGeneratedKeys();
                        if (rsNewHouse.next()) {
                            houseId = rsNewHouse.getInt(1);
                            finalHouseName = data.houseData;
                            finalRole = "ADMIN";
                        }
                    }

                    if (houseId == null) {
                        // Se chegou aqui e não tem houseId, é porque falhou ao criar ou entrar na casa.
                        throw new Exception("Falha crítica: Nenhuma residência foi associada ao usuário.");
                    }

                    // C. Inserir o Usuário (Já com o house_id correto!)
                    if (houseId == null) {
                        throw new Exception("Falha crítica: Nenhuma residência foi associada.");
                    }

                    int newUserId = 0;

                    if (ghostId != null) {
                        // === RESSUSCITAR FANTASMA ===
                        String sqlRevive = "UPDATE users SET name = ?, email = ?, password_hash = ?, house_id = ?, role = ?, active = TRUE WHERE id = ?";
                        PreparedStatement stmtRevive = conn.prepareStatement(sqlRevive);
                        stmtRevive.setString(1, data.name);
                        stmtRevive.setString(2, data.email);
                        stmtRevive.setString(3, data.password); // Atualiza com a NOVA SENHA
                        stmtRevive.setInt(4, houseId);
                        stmtRevive.setString(5, finalRole);
                        stmtRevive.setInt(6, ghostId);
                        stmtRevive.executeUpdate();
                        newUserId = ghostId; // Mantém o ID antigo
                    } else {
                        // === CRIAR NOVO USUÁRIO NORMAL ===
                        String sqlUser = "INSERT INTO users (name, email, password_hash, house_id, role) VALUES (?, ?, ?, ?, ?)";
                        PreparedStatement stmtUser = conn.prepareStatement(sqlUser, Statement.RETURN_GENERATED_KEYS);
                        stmtUser.setString(1, data.name);
                        stmtUser.setString(2, data.email);
                        stmtUser.setString(3, data.password);
                        stmtUser.setInt(4, houseId);
                        stmtUser.setString(5, finalRole);
                        stmtUser.executeUpdate();

                        ResultSet rsUser = stmtUser.getGeneratedKeys();
                        if (rsUser.next()) {
                            newUserId = rsUser.getInt(1);
                        }
                    }

                    // --- SUCESSO TOTAL: CONFIRMA TUDO NO BANCO ---
                    conn.commit();

                    // Prepara resposta
                    JsonObject json = new JsonObject();
                    json.addProperty("success", true);
                    json.addProperty("message", "Bem-vindo à Matrix.");

                    JsonObject userJson = new JsonObject();
                    userJson.addProperty("id", newUserId);
                    userJson.addProperty("name", data.name);
                    userJson.addProperty("house_id", houseId);
                    userJson.addProperty("house_name", finalHouseName);
                    userJson.addProperty("role", finalRole);
                    if (generatedInvite != null) {
                        userJson.addProperty("invite_code", generatedInvite);
                    }

                    json.add("user", userJson);
                    out.print(gson.toJson(json));

                } catch (Exception ex) {
                    // --- DEU ERRO? DESFAZ TUDO! ---
                    conn.rollback();
                    throw ex; // Repassa o erro para o catch de fora
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            out.print("{\"success\": false, \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    // Classe auxiliar para receber TUDO de uma vez
    private class FullRegistrationData {

        String name;
        String email;
        String password;
        String houseAction; // "CREATE" ou "JOIN"
        String houseData;   // Nome da casa OU Código
    }
}
