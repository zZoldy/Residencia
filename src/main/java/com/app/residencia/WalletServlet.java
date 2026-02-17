/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.residencia;

import com.app.dto.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(name = "WalletServlet", urlPatterns = {"/api/wallet"})
public class WalletServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();

        String houseIdParam = request.getParameter("houseId");
        if (houseIdParam == null || houseIdParam.isEmpty()) {
            response.setStatus(400);
            out.print("{\"success\": false}");
            return;
        }

        int houseId = Integer.parseInt(houseIdParam);

        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "SELECT t.*, u.name as user_name, u.active as user_active FROM transactions t "
                    + "JOIN users u ON t.user_id = u.id "
                    + "WHERE t.house_id = ? ORDER BY t.created_at DESC";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, houseId);
            ResultSet rs = stmt.executeQuery();

            JsonArray transactions = new JsonArray();
            double gastoMensal = 0.0;
            double pending = 0.0;
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");

            int currentMonth = LocalDate.now().getMonthValue();
            int currentYear = LocalDate.now().getYear();

            while (rs.next()) {
                JsonObject t = new JsonObject();
                t.addProperty("id", rs.getInt("id"));
                t.addProperty("description", rs.getString("description"));

                double amount = rs.getDouble("amount");
                t.addProperty("amount", amount);

                String status = rs.getString("status");
                t.addProperty("status", status);

                t.addProperty("nf_key", rs.getString("nf_key"));
                t.addProperty("user_id", rs.getInt("user_id")); // <--- NOVA LINHA AQUI
                t.addProperty("user_name", rs.getString("user_name").split(" ")[0]);
                t.addProperty("user_active", rs.getBoolean("user_active"));

                Timestamp ts = rs.getTimestamp("created_at");
                t.addProperty("date", sdf.format(ts));

                Date dueDate = rs.getDate("due_date");
                if (dueDate != null) {
                    t.addProperty("due_date", dueDate.toString()); // Retorna "YYYY-MM-DD"
                } else {
                    t.addProperty("due_date", "");
                }

                t.addProperty("observation", rs.getString("observation") != null ? rs.getString("observation") : "");

                transactions.add(t);

                if (status.equals("PAID")) {
                    LocalDate dataTransacao = ts.toLocalDateTime().toLocalDate();
                    if (dataTransacao.getMonthValue() == currentMonth && dataTransacao.getYear() == currentYear) {
                        gastoMensal += amount;
                    }
                } else if (status.equals("PENDING")) {
                    pending += amount;
                }
            }

            String sqlMembers = "SELECT id, name, phone, pix_key, active FROM users WHERE house_id = ?";
            PreparedStatement stmtMembers = conn.prepareStatement(sqlMembers);
            stmtMembers.setInt(1, houseId);
            ResultSet rsMembers = stmtMembers.executeQuery();

            JsonArray members = new JsonArray();
            while (rsMembers.next()) {
                JsonObject m = new JsonObject();
                m.addProperty("id", rsMembers.getInt("id"));
                m.addProperty("name", rsMembers.getString("name"));
                m.addProperty("phone", rsMembers.getString("phone") != null ? rsMembers.getString("phone") : "");
                m.addProperty("pix_key", rsMembers.getString("pix_key") != null ? rsMembers.getString("pix_key") : "");
                m.addProperty("active", rsMembers.getBoolean("active"));
                members.add(m);
            }

            JsonObject jsonResponse = new JsonObject();
            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("gasto_mensal", gastoMensal);
            jsonResponse.addProperty("pending", pending);
            jsonResponse.add("transactions", transactions);
            jsonResponse.add("members", members);
            out.print(new Gson().toJson(jsonResponse));

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            out.print("{\"success\": false}");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");

        PrintWriter out = response.getWriter();
        Gson gson = new Gson();

        try {
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = request.getReader().readLine()) != null) {
                sb.append(s);
            }
            TransactionRequest req = gson.fromJson(sb.toString(), TransactionRequest.class);

            String action = req.action != null ? req.action : "CREATE";

            try (Connection conn = DatabaseManager.getConnection()) {

                if ("CREATE".equals(action)) {
                    if (!DatabaseManager.isValid(req.description, "TEXTO") || req.amount <= 0) {
                        out.print("{\"success\": false, \"message\": \"Dados inválidos.\"}");
                        return;
                    }

                    // === LÓGICA DO RATEIO (DIVIDIR CONTA) ===
                    if (req.isShared) {
                        // 1. Descobre quem mora na casa
                        String sqlUsers = "SELECT id, name FROM users WHERE house_id = ? AND active = TRUE";
                        PreparedStatement stmtUsers = conn.prepareStatement(sqlUsers);
                        stmtUsers.setInt(1, req.house_id);
                        ResultSet rsUsers = stmtUsers.executeQuery();

                        List<UserCota> moradores = new ArrayList<>();
                        while (rsUsers.next()) {
                            moradores.add(new UserCota(rsUsers.getInt("id"), rsUsers.getString("name").split(" ")[0]));
                        }

                        if (moradores.isEmpty()) {
                            throw new Exception("Casa vazia.");
                        }

                        // 2. Divide o valor
                        double valorDividido = req.amount / moradores.size();

                        // 3. Insere uma conta para cada um
                        String sqlInsert = "INSERT INTO transactions (house_id, user_id, description, amount, nf_key, status, due_date, observation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                        PreparedStatement stmtInsert = conn.prepareStatement(sqlInsert);

                        for (UserCota m : moradores) {
                            stmtInsert.setInt(1, req.house_id);
                            stmtInsert.setInt(2, m.id);
                            stmtInsert.setString(3, req.description + " (Cota " + m.nome + ")");
                            stmtInsert.setDouble(4, valorDividido);

                            if (req.nf_key != null && !req.nf_key.trim().isEmpty()) {
                                stmtInsert.setString(5, req.nf_key);
                            } else {
                                stmtInsert.setNull(5, Types.VARCHAR);
                            }

                            // Se for a conta do usuário que registrou, aplica o status que ele escolheu.
                            // Se for a conta dos outros, joga como PENDENTE obrigatoriamente.
                            if (m.id == req.user_id) {
                                stmtInsert.setString(6, req.status);
                            } else {
                                stmtInsert.setString(6, "PENDING");
                            }

                            // O 7º parâmetro será a Data:
                            if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                                stmtInsert.setDate(7, java.sql.Date.valueOf(req.due_date));
                            } else {
                                stmtInsert.setNull(7, Types.DATE);
                            }

                            if (req.observation != null && !req.observation.trim().isEmpty()) {
                                stmtInsert.setString(8, req.observation);
                            } else {
                                stmtInsert.setNull(8, Types.VARCHAR);
                            }

                            stmtInsert.executeUpdate();
                        }
                        out.print("{\"success\": true, \"message\": \"Despesa rateada com sucesso!\"}");

                    } else {
                        // === LÓGICA INDIVIDUAL NORMAL ===
                        String sql = "INSERT INTO transactions (house_id, user_id, description, amount, nf_key, status, due_date, observation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                        PreparedStatement stmt = conn.prepareStatement(sql);
                        stmt.setInt(1, req.house_id);
                        stmt.setInt(2, req.user_id);
                        stmt.setString(3, req.description);
                        stmt.setDouble(4, req.amount);

                        if (req.nf_key != null && !req.nf_key.trim().isEmpty()) {
                            stmt.setString(5, req.nf_key);
                        } else {
                            stmt.setNull(5, Types.VARCHAR);
                        }

                        stmt.setString(6, req.status);
                        if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                            stmt.setDate(7, java.sql.Date.valueOf(req.due_date));
                        } else {
                            stmt.setNull(7, Types.DATE);
                        }

                        // Parâmetro 8: Observação
                        if (req.observation != null && !req.observation.trim().isEmpty()) {
                            stmt.setString(8, req.observation);
                        } else {
                            stmt.setNull(8, Types.VARCHAR);
                        }

                        stmt.executeUpdate();
                        out.print("{\"success\": true, \"message\": \"Despesa registrada.\"}");
                    }

                } else if ("EDIT".equals(action)) {
                    // === LÓGICA DE EDIÇÃO ===
                    // Atualiza apenas os campos de texto, data e status. (Não mexe no valor para não quebrar rateios)
                    String sqlUpdate = "UPDATE transactions SET description = ?, due_date = ?, observation = ?, nf_key = ?, status = ? WHERE id = ? AND house_id = ? AND user_id = ?";
                    PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate);

                    stmtUpdate.setString(1, req.description);

                    if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                        stmtUpdate.setDate(2, java.sql.Date.valueOf(req.due_date));
                    } else {
                        stmtUpdate.setNull(2, Types.DATE);
                    }

                    if (req.observation != null && !req.observation.trim().isEmpty()) {
                        stmtUpdate.setString(3, req.observation);
                    } else {
                        stmtUpdate.setNull(3, Types.VARCHAR);
                    }

                    if (req.nf_key != null && !req.nf_key.trim().isEmpty()) {
                        stmtUpdate.setString(4, req.nf_key);
                    } else {
                        stmtUpdate.setNull(4, Types.VARCHAR);
                    }

                    stmtUpdate.setString(5, req.status);

                    // Condições de segurança (Onde = ID da transação, ID da casa e ID do Operador)
                    stmtUpdate.setInt(6, req.transaction_id);
                    stmtUpdate.setInt(7, req.house_id);
                    stmtUpdate.setInt(8, req.user_id);

                    int rows = stmtUpdate.executeUpdate();
                    if (rows > 0) {
                        out.print("{\"success\": true, \"message\": \"Registro atualizado na Matrix.\"}");
                    } else {
                        out.print("{\"success\": false, \"message\": \"Erro: Você não tem permissão para editar esta conta.\"}");
                    }
                } else if ("PAY".equals(action)) {
                    String sql = "UPDATE transactions SET status = 'PAID' WHERE id = ? AND house_id = ?";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setInt(1, req.transaction_id);
                    stmt.setInt(2, req.house_id);
                    stmt.executeUpdate();
                    out.print("{\"success\": true, \"message\": \"Conta PAGA!\"}");
                } else if ("CANCEL".equals(action)) {
                    String sql = "UPDATE transactions SET status = 'CANCELED' WHERE id = ? AND house_id = ?";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setInt(1, req.transaction_id);
                    stmt.setInt(2, req.house_id);
                    stmt.executeUpdate();
                    out.print("{\"success\": true, \"message\": \"Registro CANCELADO.\"}");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            out.print("{\"success\": false, \"message\": \"Erro: " + e.getMessage() + "\"}");
        }
    }

    // Estruturas auxiliares
    private class TransactionRequest {

        String action;
        int transaction_id;
        int house_id;
        int user_id;
        String description;
        double amount;
        String nf_key;
        String status;
        boolean isShared; // <--- NOVO CAMPO
        String due_date;
        String observation;
    }

    private class UserCota {

        int id;
        String nome;

        UserCota(int id, String nome) {
            this.id = id;
            this.nome = nome;
        }
    }
}
