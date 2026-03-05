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

@WebServlet(name = "ProfileServlet", urlPatterns = {"/api/profile"})
public class ProfileServlet extends HttpServlet {

    // === BUSCAR DADOS DO PERFIL ===
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();

        String userIdParam = request.getParameter("userId");
        if (userIdParam == null) {
            return;
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            String sql = "SELECT name, phone, pix_key FROM users WHERE id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, Integer.parseInt(userIdParam));
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                JsonObject user = new JsonObject();
                user.addProperty("name", rs.getString("name"));
                user.addProperty("phone", rs.getString("phone") != null ? rs.getString("phone") : "");
                user.addProperty("pix_key", rs.getString("pix_key") != null ? rs.getString("pix_key") : "");

                JsonObject jsonResponse = new JsonObject();
                jsonResponse.addProperty("success", true);
                jsonResponse.add("user", user);
                out.print(new Gson().toJson(jsonResponse));
            }
        } catch (Exception e) {
            e.printStackTrace();
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
            ProfileRequest req = gson.fromJson(sb.toString(), ProfileRequest.class);

            String action = req.action != null ? req.action : "UPDATE";

            try (Connection conn = DatabaseManager.getConnection()) {

                if ("UPDATE".equals(action)) {
                    // === SALVAR PERFIL ===
                    String sql = "UPDATE users SET name = ?, phone = ?, pix_key = ? WHERE id = ?";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, req.name);
                    stmt.setString(2, req.phone);
                    stmt.setString(3, req.pix_key);
                    stmt.setInt(4, req.user_id);
                    stmt.executeUpdate();

                    out.print("{\"success\": true, \"message\": \"Perfil atualizado.\"}");

                } else if ("DELETE".equals(action)) {
                    // === LÓGICA DE DESTRUIÇÃO ===
                    conn.setAutoCommit(false); 

                    try {
                        String sqlCount = "SELECT COUNT(id) AS total FROM users WHERE house_id = ?";
                        PreparedStatement stmtCount = conn.prepareStatement(sqlCount);
                        stmtCount.setInt(1, req.house_id);
                        ResultSet rsCount = stmtCount.executeQuery();
                        rsCount.next();
                        int totalMoradores = rsCount.getInt("total");

                        if (totalMoradores == 1) {

                            String sqlDelHouse = "DELETE FROM houses WHERE id = ?";
                            PreparedStatement stmtDelHouse = conn.prepareStatement(sqlDelHouse);
                            stmtDelHouse.setInt(1, req.house_id);
                            stmtDelHouse.executeUpdate();

                        } else {
                            if ("ADMIN".equals(req.role)) {
                                String sqlNextAdmin = "SELECT id FROM users WHERE house_id = ? AND id != ? ORDER BY created_at ASC LIMIT 1";
                                PreparedStatement stmtNext = conn.prepareStatement(sqlNextAdmin);
                                stmtNext.setInt(1, req.house_id);
                                stmtNext.setInt(2, req.user_id);
                                ResultSet rsNext = stmtNext.executeQuery();

                                if (rsNext.next()) {
                                    int nextAdminId = rsNext.getInt("id");
                                    String sqlPromote = "UPDATE users SET role = 'ADMIN' WHERE id = ?";
                                    PreparedStatement stmtPromote = conn.prepareStatement(sqlPromote);
                                    stmtPromote.setInt(1, nextAdminId);
                                    stmtPromote.executeUpdate();
                                }
                            }

                            String sqlGhost = "UPDATE users SET active = FALSE, email = CONCAT('del_', id, '_', email), password_hash = 'DELETED' WHERE id = ?";
                            PreparedStatement stmtDelUser = conn.prepareStatement(sqlGhost);
                            stmtDelUser.setInt(1, req.user_id);
                            stmtDelUser.executeUpdate();
                        }

                        conn.commit();
                        out.print("{\"success\": true, \"message\": \"Usuário desintegrado com sucesso.\"}");

                    } catch (Exception ex) {
                        conn.rollback();
                        throw ex;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            out.print("{\"success\": false, \"message\": \"Erro fatal na Matrix.\"}");
        }
    }

    private class ProfileRequest {

        String action;
        int user_id;
        int house_id;
        String role;
        String name;
        String phone;
        String pix_key;
    }
}
