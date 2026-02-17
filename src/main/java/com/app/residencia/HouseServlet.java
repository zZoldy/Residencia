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

@WebServlet(name = "HouseServlet", urlPatterns = {"/api/house"})
public class HouseServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();
        Gson gson = new Gson();

        try {
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = request.getReader().readLine()) != null) sb.append(s);
            HouseRequest reqData = gson.fromJson(sb.toString(), HouseRequest.class);

            try (Connection conn = DatabaseManager.getConnection()) {

                // === NOVA LÓGICA: VERIFICAR SE O NOME EXISTE ===
                if ("CHECK_NAME".equals(reqData.action)) {
                    
                    String sql = "SELECT id FROM houses WHERE name = ?";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, reqData.houseName);
                    ResultSet rs = stmt.executeQuery();

                    JsonObject json = new JsonObject();
                    if (rs.next()) {
                        json.addProperty("exists", true);
                        json.addProperty("message", "Esta república já existe.");
                    } else {
                        json.addProperty("exists", false);
                        json.addProperty("message", "Nome disponível.");
                    }
                    out.print(gson.toJson(json));

                } 
                // ... Mantivemos o resto do código para compatibilidade, 
                // mas lembre-se que o cadastro real agora é feito pelo RegisterServlet.
                // Esse Servlet aqui fica focado em verificações auxiliares.
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            out.print("{\"success\": false, \"message\": \"Erro: " + e.getMessage() + "\"}");
        }
    }

    private class HouseRequest {
        String action;     
        String houseName;   
        // Outros campos opcionais
        String inviteCode;
        int userId;
    }
}