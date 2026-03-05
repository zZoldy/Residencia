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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        String userIdParam = request.getParameter("userId");
        if (houseIdParam == null || houseIdParam.isEmpty()) {
            response.setStatus(400);
            out.print("{\"success\": false}");
            return;
        }

        int houseId = Integer.parseInt(houseIdParam);
        int loggedUserId = (userIdParam != null) ? Integer.parseInt(userIdParam) : 0; 

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
            double meusGastos = 0.0;
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");

            int currentMonth = LocalDate.now().getMonthValue();
            int currentYear = LocalDate.now().getYear();

            while (rs.next()) {
                JsonObject t = new JsonObject();
                int transId = rs.getInt("id");
                String description = rs.getString("description"); 
                int transUserId = rs.getInt("user_id");
                double amount = rs.getDouble("amount");
                String status = rs.getString("status");
                Timestamp ts = rs.getTimestamp("created_at");

                t.addProperty("id", transId);
                t.addProperty("description", description);
                t.addProperty("amount", amount);
                t.addProperty("status", status);
                t.addProperty("nf_key", rs.getString("nf_key"));
                t.addProperty("user_id", transUserId);
                t.addProperty("user_name", rs.getString("user_name"));
                t.addProperty("user_active", rs.getBoolean("user_active"));
                t.addProperty("date", sdf.format(ts));

                Date dueDate = rs.getDate("due_date");
                t.addProperty("due_date", (dueDate != null) ? dueDate.toString() : "");
                t.addProperty("observation", rs.getString("observation") != null ? rs.getString("observation") : "");

                try {
                    String sqlItems = "SELECT * FROM transaction_items WHERE transaction_id = ?";
                    PreparedStatement stmtItems = conn.prepareStatement(sqlItems);
                    stmtItems.setInt(1, transId);
                    ResultSet rsItems = stmtItems.executeQuery();
                    JsonArray itensArray = new JsonArray();
                    while (rsItems.next()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", rsItems.getString("name"));
                        item.addProperty("quantity", rsItems.getDouble("quantity"));
                        item.addProperty("price", rsItems.getDouble("price"));
                        item.addProperty("owner", rsItems.getString("owner"));
                        itensArray.add(item);
                    }
                    if (itensArray.size() > 0) {
                        t.add("items", itensArray);
                    }
                } catch (Exception ex) {
                }

                transactions.add(t);

                boolean isPessoal = description.toUpperCase().contains("[PESSOAL]");

                if (status.equals("PAID")) {
                    LocalDate dataTransacao = ts.toLocalDateTime().toLocalDate();

                    if (dataTransacao.getMonthValue() == currentMonth && dataTransacao.getYear() == currentYear) {

                        if (isPessoal) {
                            if (transUserId == loggedUserId) {
                                meusGastos += amount;
                            }
                        } else {
                            gastoMensal += amount;
                            if (transUserId == loggedUserId) {
                                meusGastos += amount;
                            }
                        }
                    }
                } else if (status.equals("PENDING")) {
                    if (!isPessoal) {
                        pending += amount;
                    }
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
            jsonResponse.addProperty("meus_gastos", meusGastos);
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

            System.out.println(">> LOG MATRIX [1]: Payload Recebido -> " + sb.toString());

            TransactionRequest req = gson.fromJson(sb.toString(), TransactionRequest.class);
            String action = req.action != null ? req.action : "CREATE";

            System.out.println(">> LOG MATRIX [2]: Ação = " + action + " | Qtd Itens Lidos = " + (req.items != null ? req.items.size() : "NULL"));

            try (Connection conn = DatabaseManager.getConnection()) {

               if ("CREATE".equals(action)) {
                    if (!DatabaseManager.isValid(req.description, "TEXTO") || req.amount <= 0) {
                        out.print("{\"success\": false, \"message\": \"Dados inválidos.\"}");
                        return;
                    }

                    double amountCasa = 0;
                    List<ItemNota> itensCasa = new ArrayList<>();

                    Map<Integer, Double> amountPorUsuario = new HashMap<>();
                    Map<Integer, List<ItemNota>> itensPorUsuario = new HashMap<>();

                    if (req.items != null && !req.items.isEmpty()) {
                        for (ItemNota item : req.items) {
                            if ("HOUSE".equals(item.owner)) {
                                amountCasa += item.price;
                                itensCasa.add(item);
                            } else {
                                int donoId = req.user_id;
                                if (item.owner.startsWith("USER_")) {
                                    try {
                                        donoId = Integer.parseInt(item.owner.replace("USER_", ""));
                                    } catch (Exception e) {
                                        donoId = req.user_id; 
                                    }
                                } else if ("ME".equals(item.owner)) {
                                    donoId = req.user_id;
                                }

                                amountPorUsuario.put(donoId, amountPorUsuario.getOrDefault(donoId, 0.0) + item.price);
                                itensPorUsuario.putIfAbsent(donoId, new ArrayList<>());
                                itensPorUsuario.get(donoId).add(item);
                            }
                        }
                    } else {
                        amountCasa = req.amount; 
                    }

                    if (amountCasa > 0) {
                        if (req.isShared) {
                            String sqlUsers = "SELECT id, name FROM users WHERE house_id = ? AND active = TRUE";
                            PreparedStatement stmtUsers = conn.prepareStatement(sqlUsers);
                            stmtUsers.setInt(1, req.house_id);
                            ResultSet rsUsers = stmtUsers.executeQuery();

                            List<UserCota> moradores = new ArrayList<>();
                            while (rsUsers.next()) {
                                moradores.add(new UserCota(rsUsers.getInt("id"), rsUsers.getString("name")));
                            }

                            if (moradores.isEmpty()) {
                                throw new Exception("Casa vazia.");
                            }

                            double valorDividido = amountCasa / moradores.size();

                            String sqlInsert = "INSERT INTO transactions (house_id, user_id, description, amount, nf_key, status, due_date, observation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                            PreparedStatement stmtInsert = conn.prepareStatement(sqlInsert, Statement.RETURN_GENERATED_KEYS);

                            for (UserCota m : moradores) {
                                stmtInsert.setInt(1, req.house_id);
                                stmtInsert.setInt(2, m.id);
                                stmtInsert.setString(3, req.description + " (Cota " + m.nome + ")");
                                stmtInsert.setDouble(4, valorDividido);
                                stmtInsert.setString(5, (req.nf_key != null && !req.nf_key.trim().isEmpty()) ? req.nf_key : null);
                                stmtInsert.setString(6, (m.id == req.user_id) ? req.status : "PENDING");

                                if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                                    stmtInsert.setDate(7, java.sql.Date.valueOf(req.due_date));
                                } else {
                                    stmtInsert.setNull(7, Types.DATE);
                                }

                                stmtInsert.setString(8, (req.observation != null && !req.observation.trim().isEmpty()) ? req.observation : null);
                                stmtInsert.executeUpdate();

                                try (ResultSet keys = stmtInsert.getGeneratedKeys()) {
                                    if (keys.next()) {
                                        int cotaId = keys.getInt(1);
                                        if (!itensCasa.isEmpty()) {
                                            DatabaseManager.salvarItensNota(cotaId, gson.toJson(itensCasa));
                                        }
                                    }
                                }
                            }
                        } else {
                            String sql = "INSERT INTO transactions (house_id, user_id, description, amount, nf_key, status, due_date, observation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                            PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

                            stmt.setInt(1, req.house_id);
                            stmt.setInt(2, req.user_id);
                            stmt.setString(3, req.description);
                            stmt.setDouble(4, amountCasa);
                            stmt.setString(5, (req.nf_key != null && !req.nf_key.trim().isEmpty()) ? req.nf_key : null);
                            stmt.setString(6, req.status);

                            if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                                stmt.setDate(7, java.sql.Date.valueOf(req.due_date));
                            } else {
                                stmt.setNull(7, Types.DATE);
                            }

                            stmt.setString(8, (req.observation != null && !req.observation.trim().isEmpty()) ? req.observation : null);
                            stmt.executeUpdate();

                            try (ResultSet keys = stmt.getGeneratedKeys()) {
                                if (keys.next()) {
                                    int idGerado = keys.getInt(1);
                                    if (!itensCasa.isEmpty()) {
                                        DatabaseManager.salvarItensNota(idGerado, gson.toJson(itensCasa));
                                    }
                                }
                            }
                        }
                    }

                    for (Map.Entry<Integer, Double> entry : amountPorUsuario.entrySet()) {
                        int idMorador = entry.getKey();
                        double amountPessoal = entry.getValue();

                        if (amountPessoal > 0) {
                            String sqlPess = "INSERT INTO transactions (house_id, user_id, description, amount, nf_key, status, due_date, observation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                            PreparedStatement stmtPess = conn.prepareStatement(sqlPess, Statement.RETURN_GENERATED_KEYS);

                            stmtPess.setInt(1, req.house_id);
                            stmtPess.setInt(2, idMorador);

                            String descPessoal = req.description;
                            if (!descPessoal.toUpperCase().contains("[PESSOAL]")) {
                                descPessoal = "[PESSOAL] " + descPessoal;
                            }
                            stmtPess.setString(3, descPessoal);
                            stmtPess.setDouble(4, amountPessoal);
                            stmtPess.setString(5, (req.nf_key != null && !req.nf_key.trim().isEmpty()) ? req.nf_key : null);
                            
                            String statusPessoal = (idMorador == req.user_id) ? req.status : "PENDING";
                            stmtPess.setString(6, statusPessoal);

                            if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                                stmtPess.setDate(7, java.sql.Date.valueOf(req.due_date));
                            } else {
                                stmtPess.setNull(7, Types.DATE);
                            }

                            stmtPess.setString(8, (req.observation != null && !req.observation.trim().isEmpty()) ? req.observation : null);
                            stmtPess.executeUpdate();

                            try (ResultSet keys = stmtPess.getGeneratedKeys()) {
                                if (keys.next()) {
                                    int idPessoal = keys.getInt(1);
                                    List<ItemNota> itensDesteMorador = itensPorUsuario.get(idMorador);
                                    if (itensDesteMorador != null && !itensDesteMorador.isEmpty()) {
                                        DatabaseManager.salvarItensNota(idPessoal, gson.toJson(itensDesteMorador));
                                    }
                                }
                            }
                        }
                    }

                    out.print("{\"success\": true, \"message\": \"Despesa processada com sucesso! Rateios e itens distribuídos.\"}");
                } else if ("EDIT".equals(action)) {

                    String sqlUpdate = "UPDATE transactions SET description = ?, amount = ?, due_date = ?, observation = ?, nf_key = ?, status = ? WHERE id = ? AND house_id = ? AND user_id = ?";
                    PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate);

                    stmtUpdate.setString(1, req.description);
                    stmtUpdate.setDouble(2, req.amount); 

                    if (req.due_date != null && !req.due_date.trim().isEmpty()) {
                        stmtUpdate.setDate(3, java.sql.Date.valueOf(req.due_date));
                    } else {
                        stmtUpdate.setNull(3, Types.DATE);
                    }

                    stmtUpdate.setString(4, (req.observation != null && !req.observation.trim().isEmpty()) ? req.observation : null);
                    stmtUpdate.setString(5, (req.nf_key != null && !req.nf_key.trim().isEmpty()) ? req.nf_key : null);
                    stmtUpdate.setString(6, req.status);

                    stmtUpdate.setInt(7, req.transaction_id);
                    stmtUpdate.setInt(8, req.house_id);
                    stmtUpdate.setInt(9, req.user_id);

                    int rows = stmtUpdate.executeUpdate();
                    if (rows > 0) {
                        out.print("{\"success\": true, \"message\": \"Informações da conta atualizadas com sucesso.\"}");
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

    // ============================================================
    // CLASSES AUXILIARES (BLINDADAS PARA O GSON NÃO FALHAR)
    // ============================================================
    private class TransactionRequest {

        String action;
        int transaction_id;
        int house_id;
        int user_id;
        String description;
        double amount;
        String nf_key;
        String status;
        boolean isShared;
        String due_date;
        String observation;

        List<ItemNota> items;
    }

    private class ItemNota {

        String name;
        double quantity;
        double price;
        String owner;
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
